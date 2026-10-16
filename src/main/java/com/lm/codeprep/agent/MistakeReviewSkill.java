package com.lm.codeprep.agent;

import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.knowledge.KnowledgeModels.Citation;
import com.lm.codeprep.knowledge.KnowledgeModels.RetrievalResult;
import com.lm.codeprep.knowledge.KnowledgeSearchService;
import com.lm.codeprep.knowledge.RagPromptBuilder;
import com.lm.codeprep.practice.PracticeAgentService;
import com.lm.codeprep.practice.PracticeModels.AttemptReview;
import com.lm.codeprep.practice.PracticeModels.Question;
import com.lm.codeprep.practice.PracticeModels.ReviewData;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reviews one user's practice attempts and starts a source-grounded focused practice session. */
@Component
public class MistakeReviewSkill {
    public static final String ID = "MISTAKE_REVIEW";
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[([1-9][0-9]*)]");

    private final PracticeAgentService practice;
    private final KnowledgeSearchService search;
    private final DeepSeekClient llm;
    private final RagPromptBuilder prompts;

    public MistakeReviewSkill(PracticeAgentService practice, KnowledgeSearchService search,
                              DeepSeekClient llm, RagPromptBuilder prompts) {
        this.practice = practice; this.search = search; this.llm = llm; this.prompts = prompts;
    }

    public Outcome run(String username, String practiceSessionId, String category, String orgTag) {
        if (practiceSessionId == null || practiceSessionId.isBlank())
            throw new IllegalArgumentException("错题复盘需要 practiceSessionId");
        ReviewData history = practice.reviewData(username, practiceSessionId);
        if (!history.category().equals(category) || !history.orgTag().equals(orgTag))
            throw new SecurityException("刷题会话与当前知识域不匹配");
        if (history.attempts().isEmpty())
            return new Outcome("NEEDS_PRACTICE_HISTORY", "这组题还没有提交过答案，请先完成至少一题。",
                    List.of(), List.of(), null);

        List<String> weakConcepts = weakConcepts(history.attempts());
        if (weakConcepts.isEmpty())
            return new Outcome("NO_WEAKNESS", "本组答案没有识别出需要复练的薄弱知识点。",
                    List.of(), List.of(), null);

        String focus = weakConcepts.get(0);
        RetrievalResult retrieval = history.groupName() == null
                ? search.retrieve(focus, orgTag, category)
                : search.retrieve(focus, orgTag, category, history.groupName());
        if ("PRIVATE_INDEX_UNAVAILABLE".equals(retrieval.strategy()))
            return new Outcome("RETRIEVAL_UNAVAILABLE", "私有知识库检索暂时不可用，无法依据资料生成复练题。",
                    weakConcepts, List.of(), null);
        List<Citation> evidence = retrieval.citations().stream().limit(3).toList();
        if (evidence.isEmpty())
            return new Outcome("NEEDS_SOURCE", "发现薄弱点“" + focus + "”，但当前知识域没有可引用的复习资料。",
                    weakConcepts, List.of(), null);

        String explanation = explain(focus, weakConcepts, history.attempts(), evidence);
        Question question = history.groupName() == null
                ? practice.startFocused(username, category, orgTag, focus, evidence)
                : practice.startFocused(username, category, orgTag, history.groupName(), focus, evidence);
        return new Outcome(validCitations(explanation, evidence.size()) ? "COMPLETED" : "REVIEW_REQUIRED",
                explanation, weakConcepts, evidence, question);
    }

    private List<String> weakConcepts(List<AttemptReview> attempts) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AttemptReview attempt : attempts) {
            for (String missing : attempt.missingConcepts().stream().distinct().toList()) {
                String concept = missing == null ? "" : missing.strip();
                if (!concept.isEmpty()) counts.merge(concept.substring(0, Math.min(80, concept.length())), 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            attempts.stream().filter(attempt -> attempt.score() < 70)
                    .min(Comparator.comparingInt(AttemptReview::score))
                    .ifPresent(attempt -> counts.put(attempt.questionPrompt().substring(0,
                            Math.min(100, attempt.questionPrompt().length())), 1));
        }
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3).map(Map.Entry::getKey).toList();
    }

    private String explain(String focus, List<String> weakConcepts, List<AttemptReview> attempts, List<Citation> evidence) {
        String fallback = "主要薄弱点：" + String.join("、", weakConcepts) + "。请先复习“" + focus + "”："
                + evidence.get(0).content().substring(0, Math.min(320, evidence.get(0).content().length()))
                + " [1]\n接下来完成针对性复练题。";
        if (!llm.enabled()) return fallback;
        try {
            String question = "根据这组练习的失分点做简短复盘。重点：" + focus + "；其他薄弱点："
                    + String.join("、", weakConcepts) + "；已提交 " + attempts.size()
                    + " 题。解释容易混淆的原理、给出改进建议，不要直接回答即将出现的复练题。";
            String generated = llm.chat("你是 Java 学习错题复盘 Skill。仅依据 CONTEXT 解释，引用证据使用 [1] 等编号；资料不足时说明限制。",
                    prompts.userPrompt(question, List.of(), evidence)).block();
            return generated == null || generated.isBlank() ? fallback : generated;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private boolean validCitations(String explanation, int count) {
        Matcher matcher = CITATION_MARKER.matcher(explanation);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            try {
                if (Integer.parseInt(matcher.group(1)) > count) return false;
            } catch (NumberFormatException ignored) { return false; }
        }
        return found;
    }

    public record Outcome(String status, String explanation, List<String> weakConcepts,
                          List<Citation> citations, Question question) { }
}
