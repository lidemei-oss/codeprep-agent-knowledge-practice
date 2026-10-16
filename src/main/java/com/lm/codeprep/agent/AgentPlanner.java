package com.lm.codeprep.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.infra.DeepSeekClient;
import org.springframework.stereotype.Component;
import static com.lm.codeprep.agent.AgentModels.RunRequest;

/** Model proposals are constrained to the explicit tool allowlist. */
@Component
public class AgentPlanner {
    public enum Action { SEARCH_KNOWLEDGE, START_PRACTICE, GRADE_ANSWER, REVIEW_MISTAKES }
    private final DeepSeekClient llm;
    private final ObjectMapper mapper;
    public AgentPlanner(DeepSeekClient llm, ObjectMapper mapper) { this.llm = llm; this.mapper = mapper; }

    public Action decide(RunRequest request) {
        if (hasText(request.questionId()) || hasText(request.answer()) || hasText(request.requestId())) return Action.GRADE_ANSWER;
        if (hasText(request.practiceSessionId())) return Action.REVIEW_MISTAKES;
        if (llm.enabled()) {
            try {
                String response = llm.chat("你是任务路由器，只输出 JSON：{\"action\":\"SEARCH_KNOWLEDGE|START_PRACTICE\"}。"
                        + "要求练习或出题时选 START_PRACTICE，其他情况选 SEARCH_KNOWLEDGE。", request.task())
                        .timeout(java.time.Duration.ofSeconds(30)).block();
                JsonNode node = mapper.readTree(response);
                Action proposal = Action.valueOf(node.path("action").asText());
                if (proposal != Action.GRADE_ANSWER) return proposal;
            } catch (Exception ignored) { }
        }
        return java.util.regex.Pattern.compile("刷题|练习|出题|测验|做题|quiz|practice", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(request.task()).find() ? Action.START_PRACTICE : Action.SEARCH_KNOWLEDGE;
    }

    public String rewrite(String question) {
        if (!llm.enabled()) return question.replaceAll("请问|能不能|请详细|帮我|介绍一下|是什么", "").trim();
        try {
            String rewritten = llm.chat("把用户问题改写为适合技术知识库检索的单行查询，只返回查询文本。"
                    + "保留原问题的实体、条件和意图，不添加事实，不回答问题。", question)
                    .timeout(java.time.Duration.ofSeconds(30)).block();
            return rewritten == null || rewritten.isBlank() ? question : rewritten.strip().substring(0, Math.min(200, rewritten.strip().length()));
        } catch (RuntimeException ignored) { return question; }
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
