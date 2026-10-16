package com.lm.codeprep.knowledge;

import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.infra.RedisContextStore;
import com.lm.codeprep.auth.SessionScope;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.time.Duration;

import static com.lm.codeprep.knowledge.KnowledgeModels.*;

@Service
public class KnowledgeAgentService {
    private final GroundedKnowledgeService grounded;
    private final RagPromptBuilder promptBuilder;
    private final DeepSeekClient llm;
    private final RedisContextStore context;
    private final SessionScope sessions;

    public KnowledgeAgentService(GroundedKnowledgeService grounded, RagPromptBuilder promptBuilder,
                                 DeepSeekClient llm, RedisContextStore context, SessionScope sessions) {
        this.grounded = grounded; this.promptBuilder = promptBuilder; this.llm = llm; this.context = context;
        this.sessions = sessions;
    }
/*    前端提问调用 ask()
    → 限流校验
    → 获取会话
    → retrieve：向量库检索该orgTag+category下的文档片段（可选联网搜索）
            → 读取对话历史，组装prompt
    → 调用LLM生成答案（无检索结果则兜底）
            → 保存本轮问答到会话历史
    → 返回RagResponse，携带引用来源*/
    public RagResponse ask(String username, String sessionId, String question, String category, String orgTag,
                           boolean allowWebSearch) {
        // 1. 限流：针对 orgTag 做频率控制，1分钟最多60次请求
        if (!context.allow("knowledge:" + (orgTag == null ? "default" : orgTag), 60, Duration.ofMinutes(1))) {
            throw new IllegalStateException("知识问答请求过于频繁，请稍后再试");
        }
        // 2. 解析会话ID，绑定 用户+组织标签+会话
        String session = sessions.resolve(username, orgTag, sessionId);
        List<String> history = context.read(session);
        RetrievalResult retrieval = grounded.retrieve(question, orgTag, category, allowWebSearch, history);
        String userPrompt = promptBuilder.userPrompt(question, history, retrieval.citations());
        String answer = retrieval.citations().isEmpty() ? fallback(question, retrieval)
                : llm.chat(promptBuilder.systemPrompt(), userPrompt).blockOptional()
                    .filter(value -> !value.isBlank()).orElseGet(() -> fallback(question, retrieval));
        context.append(session, "USER", question); context.append(session, "ASSISTANT", answer);
        return new RagResponse(session, answer, retrieval.citations(), userPrompt, retrieval);
    }

    public Flux<AgentEvent> stream(String username, String sessionId, String question, String category, String orgTag,
                                   boolean allowWebSearch) {
        if (!context.allow("knowledge:" + (orgTag == null ? "default" : orgTag), 60, Duration.ofMinutes(1))) {
            return Flux.error(new IllegalStateException("知识问答请求过于频繁，请稍后再试"));
        }
        String session = sessions.resolve(username, orgTag, sessionId);
        List<String> history = context.read(session);
        RetrievalResult retrieval = grounded.retrieve(question, orgTag, category, allowWebSearch, history);
        String userPrompt = promptBuilder.userPrompt(question, history, retrieval.citations());
        Flux<AgentEvent> start = Flux.just(new AgentEvent("session", session, List.of()),
                new AgentEvent("retrieval", "候选 " + retrieval.rerankedHits() + " 个；有效证据 "
                        + retrieval.citations().size() + " 个；策略 " + retrieval.strategy(), retrieval.citations(), retrieval.evidence()));
        Flux<AgentEvent> content = llm.enabled() && !retrieval.citations().isEmpty()
                ? llm.stream(promptBuilder.systemPrompt(), userPrompt).map(value -> new AgentEvent("delta", value, List.of()))
                : Flux.fromArray(fallback(question, retrieval).split("(?<=。|！|？)"))
                    .map(value -> new AgentEvent("delta", value, List.of()));
        StringBuilder answer = new StringBuilder();
        return start.concatWith(content.doOnNext(event -> answer.append(event.content())))
                .concatWith(Flux.just(new AgentEvent("done", "", retrieval.citations())))
                .doOnComplete(() -> { context.append(session, "USER", question); context.append(session, "ASSISTANT", answer.toString()); });
    }

    private String fallback(String question, RetrievalResult retrieval) {
        List<Citation> citations = retrieval.citations();
        if ("PRIVATE_INDEX_UNAVAILABLE".equals(retrieval.strategy()))
            return "私有知识库检索暂时不可用，无法判定是否无结果；已跳过公开搜索，请检查 Elasticsearch 后重试。";
        if ("EVIDENCE_VERIFICATION_UNAVAILABLE".equals(retrieval.strategy()))
            return "证据筛选暂时不可用，无法确认候选片段是否支持问题；已停止回答和公开搜索，请检查模型配置后重试。";
        if ("WEB_SEARCH_SKIPPED".equals(retrieval.strategy()))
            return "当前私有知识库没有足够证据支持问题，本次已关闭公开搜索。请上传资料或换一个更具体的问题。";
        if ("WEB_SEARCH_UNAVAILABLE".equals(retrieval.strategy()))
            return "当前私有知识库没有足够证据，公开搜索服务不可用。请检查 Tavily 配置后重试。";
        if (citations.isEmpty()) return "当前知识库及公开来源没有足够证据支持问题。请上传资料或换一个更具体的问题。";
        String source = "WEB_SEARCH".equals(retrieval.strategy()) ? "公开网页" : "知识库";
        return "围绕“" + question + "”，" + source + "召回了 " + citations.size() + " 个相关片段。建议先从以下证据学习：\n" +
                citations.stream().limit(3).map(c -> "- [" + c.title() + "] " + c.content() +
                        (c.url() == null ? "" : " (" + c.url() + ")")).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
