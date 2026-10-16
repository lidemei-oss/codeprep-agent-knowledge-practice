package com.lm.codeprep.agent;

import com.lm.codeprep.auth.SessionScope;
import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.infra.RedisContextStore;
import com.lm.codeprep.knowledge.KnowledgeModels.Citation;
import com.lm.codeprep.knowledge.KnowledgeModels.RetrievalResult;
import com.lm.codeprep.knowledge.GroundedKnowledgeService;
import com.lm.codeprep.knowledge.KnowledgeModels.EvidenceReport;
import com.lm.codeprep.knowledge.RagPromptBuilder;
import com.lm.codeprep.practice.PracticeAgentService;
import com.lm.codeprep.practice.PracticeModels.AnswerRequest;
import com.lm.codeprep.practice.PracticeModels.StartRequest;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.lm.codeprep.agent.AgentModels.*;

/** A bounded plan/act/observe loop using a small explicit tool allowlist. */
@Service
public class AgentOrchestrator {
    private final AgentPlanner planner;
    private final GroundedKnowledgeService grounded;
    private final PracticeAgentService practice;
    private final RagPromptBuilder prompts;
    private final DeepSeekClient llm;
    private final RedisContextStore context;
    private final AgentRunStore runs;
    private final SessionScope sessions;
    private final MistakeReviewSkill mistakeReview;

    public AgentOrchestrator(AgentPlanner planner, GroundedKnowledgeService grounded, PracticeAgentService practice,
                             RagPromptBuilder prompts, DeepSeekClient llm, RedisContextStore context,
                             AgentRunStore runs, SessionScope sessions,
                             MistakeReviewSkill mistakeReview) {
        this.planner = planner; this.grounded = grounded; this.practice = practice; this.prompts = prompts;
        this.llm = llm; this.context = context; this.runs = runs; this.sessions = sessions;
        this.mistakeReview = mistakeReview;
    }

    public RunResult run(String username, RunRequest request) {
        String sessionId = sessions.resolve(username, request.orgTag(), request.sessionId());
        String runId = UUID.randomUUID().toString();
        List<Step> steps = new ArrayList<>();
        AgentPlanner.Action action = planner.decide(request);
        add(steps, "PLAN", "选择工具 " + action);
        RunResult result;
        switch (action) {
            case START_PRACTICE -> {
                add(steps, "RETRIEVE_FOR_PRACTICE", "从当前知识域召回题源");
                var question = practice.start(username, new StartRequest(request.category(), request.orgTag(), 3,
                        "medium", request.groupName()));
                add(steps, "START_PRACTICE", "已生成第 1 题，题源片段 " + question.sourceChunkIds().size() + " 个");
                result = new RunResult(runId, sessionId, action.name(), "COMPLETED", null, question, null,
                        List.of(), List.copyOf(steps), Instant.now());
            }
            case GRADE_ANSWER -> {
                if (blank(request.practiceSessionId()) || blank(request.questionId()) || blank(request.requestId()) || blank(request.answer())) {
                    throw new IllegalArgumentException("评测需要 practiceSessionId、questionId、requestId 和 answer");
                }
                var session = practice.session(username, request.practiceSessionId());
                if (!session.orgTag().equals(request.orgTag())) throw new SecurityException("刷题知识域不匹配");
                var evaluation = practice.answer(username, request.practiceSessionId(),
                        new AnswerRequest(request.questionId(), request.requestId(), request.answer()));
                add(steps, "GRADE_ANSWER", "得分 " + evaluation.score() + "，掌握度 " + evaluation.mastery());
                result = new RunResult(runId, sessionId, action.name(), "COMPLETED", evaluation.feedback(), null,
                        evaluation, List.of(), List.copyOf(steps), Instant.now());
            }
            case REVIEW_MISTAKES -> {
                add(steps, "LOAD_PRACTICE_HISTORY", "读取本人刷题记录并分析薄弱点");
                var review = mistakeReview.run(username, request.practiceSessionId(), request.category(), request.orgTag());
                if (!review.weakConcepts().isEmpty())
                    add(steps, "RETRIEVE_WEAK_CONCEPT", "薄弱点 " + String.join("、", review.weakConcepts())
                            + "；找到 " + review.citations().size() + " 个私有知识片段");
                if (review.question() != null)
                    add(steps, "START_FOCUSED_PRACTICE", "已创建 3 题针对性复练会话");
                String reviewSessionId = review.question() == null ? null : review.question().sessionId();
                result = new RunResult(runId, sessionId, action.name(), review.status(), review.explanation(),
                        review.question(), null, review.citations(), List.copyOf(steps), Instant.now(),
                        new SkillReport(MistakeReviewSkill.ID, request.practiceSessionId(),
                                review.weakConcepts(), reviewSessionId));
            }
            case SEARCH_KNOWLEDGE -> result = answer(request, runId, sessionId, steps);
            default -> throw new IllegalStateException("未知 Agent 动作");
        }
        runs.save(username, result);
        return result;
    }

    public RunResult get(String username, String runId) { return runs.get(username, runId); }

    private RunResult answer(RunRequest request, String runId, String sessionId, List<Step> steps) {
        List<String> history = context.read(sessionId);
        RetrievalResult retrieval = grounded.retrieve(request.task(), request.orgTag(), request.category(),
                !Boolean.FALSE.equals(request.allowWebSearch()), history);
        EvidenceReport evidence = retrieval.evidence();
        var first = evidence.attempts().get(0);
        add(steps, "SEARCH_KNOWLEDGE", "候选 " + first.candidates().size() + " 个；策略 " + first.strategy());
        for (int i = 1; i < evidence.attempts().size(); i++) {
            var attempt = evidence.attempts().get(i);
            add(steps, "WEB_SEARCH".equals(attempt.strategy()) ? "SEARCH_PUBLIC_WEB" : "REWRITE_AND_SEARCH",
                    "候选 " + attempt.candidates().size() + " 个；证据判断 " + attempt.selection().status());
        }
        if ("WEB_SEARCH_UNAVAILABLE".equals(retrieval.strategy()))
            add(steps, "SEARCH_PUBLIC_WEB", "公开搜索不可用，请检查 Tavily 配置或服务");
        add(steps, "VERIFY_EVIDENCE", "状态 " + evidence.status() + "；选用 " + retrieval.citations().size()
                + " 个片段；原因 " + evidence.reason());
        if ("PRIVATE_INDEX_UNAVAILABLE".equals(retrieval.strategy()))
            return indexUnavailable(runId, sessionId, steps, evidence);
        if ("EVIDENCE_VERIFICATION_UNAVAILABLE".equals(retrieval.strategy())
                || "WEB_SEARCH_UNAVAILABLE".equals(retrieval.strategy()))
            return new RunResult(runId, sessionId, "SEARCH_KNOWLEDGE", retrieval.strategy(),
                    "EVIDENCE_VERIFICATION_UNAVAILABLE".equals(retrieval.strategy())
                            ? "证据筛选暂时不可用，已停止生成和公开搜索，请检查模型配置后重试。"
                            : "私有证据不足，公开搜索暂时不可用，请检查 Tavily 后重试。",
                    null, null, List.of(), List.copyOf(steps), Instant.now(), null, evidence);
        if (retrieval.citations().isEmpty()) {
            add(steps, "ASK_FOR_SOURCE", "没有可用证据，停止生成");
            String message = Boolean.FALSE.equals(request.allowWebSearch())
                    ? "当前私有知识库没有可引用的资料；本次已关闭公开搜索。请上传相关文档或换一个更具体的问题。"
                    : "当前知识库及公开搜索没有可引用的资料。请检查联网搜索配置、上传相关文档，或换一个更具体的问题。";
            return new RunResult(runId, sessionId, "SEARCH_KNOWLEDGE", "NEEDS_SOURCE", message, null, null,
                    List.of(), List.copyOf(steps), Instant.now(), null, evidence);
        }
        List<Citation> citations = retrieval.citations();
        String prompt = prompts.userPrompt(request.task(), history, citations);
        boolean publicWeb = "WEB_SEARCH".equals(retrieval.strategy());
        add(steps, "BUILD_RAG_PROMPT", "组合问题、会话历史和 " + citations.size() +
                (publicWeb ? " 个公开网页片段" : " 个私有知识片段"));
        String generated = llm.chat(prompts.systemPrompt(), prompt).blockOptional().filter(s -> !s.isBlank())
                .orElseGet(() -> publicWeb ? "已找到公开网页资料，但生成模型未启用。请核查下方来源。[1]"
                        : "已找到相关资料。请阅读引用片段，再围绕问题总结原理、实现与边界条件。[1]");
        add(steps, "GENERATE_ANSWER", llm.enabled() ? "DeepSeek 已生成回答" : "模型未启用，返回证据导读");
        Matcher markers = Pattern.compile("\\[([1-9][0-9]*)]").matcher(generated);
        boolean cited = false;
        boolean invalid = false;
        while (markers.find()) {
            int number = Integer.parseInt(markers.group(1));
            cited = true;
            if (number > citations.size()) invalid = true;
        }
        boolean citationsValid = cited && !invalid;
        add(steps, "CHECK_CITATIONS", citationsValid ? "引用编号均对应召回片段；事实仍需核查" : "引用缺失或编号越界，已标为待核查");
        context.append(sessionId, "USER", request.task());
        context.append(sessionId, "ASSISTANT", generated);
        return new RunResult(runId, sessionId, "SEARCH_KNOWLEDGE", citationsValid ? "COMPLETED" : "REVIEW_REQUIRED",
                generated, null, null, citations, List.copyOf(steps), Instant.now(), null, evidence);
    }

    private RunResult indexUnavailable(String runId, String sessionId, List<Step> steps, EvidenceReport evidence) {
        add(steps, "PRIVATE_INDEX_UNAVAILABLE", "私有检索服务故障，不能判定知识库是否无结果；跳过公开搜索");
        return new RunResult(runId, sessionId, "SEARCH_KNOWLEDGE", "RETRIEVAL_UNAVAILABLE",
                "私有知识库检索暂时不可用，请检查 Elasticsearch 后重试。", null, null,
                List.of(), List.copyOf(steps), Instant.now(), null, evidence);
    }

    private void add(List<Step> steps, String action, String observation) {
        if (steps.size() >= 8) throw new IllegalStateException("Agent 已达到最大步骤数");
        steps.add(new Step(steps.size() + 1, action, observation, Instant.now()));
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
}
