package com.lm.codeprep.agent;

import com.lm.codeprep.auth.SessionScope;
import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.infra.RedisContextStore;
import com.lm.codeprep.infra.WebSearchClient;
import com.lm.codeprep.knowledge.KnowledgeModels.Citation;
import com.lm.codeprep.knowledge.KnowledgeModels.RetrievalResult;
import com.lm.codeprep.knowledge.KnowledgeSearchService;
import com.lm.codeprep.knowledge.GroundedKnowledgeService;
import com.lm.codeprep.knowledge.EvidenceSelectionService;
import com.lm.codeprep.knowledge.KnowledgeModels.EvidenceSelection;
import com.lm.codeprep.knowledge.KnowledgeModels.EvidenceStatus;
import com.lm.codeprep.knowledge.RagPromptBuilder;
import com.lm.codeprep.practice.PracticeAgentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.lm.codeprep.agent.AgentModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentOrchestratorTest {
    private AgentPlanner planner;
    private KnowledgeSearchService search;
    private DeepSeekClient llm;
    private RedisContextStore context;
    private AgentOrchestrator orchestrator;
    private WebSearchClient webSearch;
    private MistakeReviewSkill mistakeReview;
    private EvidenceSelectionService selection;

    @BeforeEach
    void setup() {
        planner = mock(AgentPlanner.class);
        search = mock(KnowledgeSearchService.class);
        llm = mock(DeepSeekClient.class);
        context = mock(RedisContextStore.class);
        webSearch = mock(WebSearchClient.class);
        mistakeReview = mock(MistakeReviewSkill.class);
        when(context.read(anyString())).thenReturn(List.of());
        selection = mock(EvidenceSelectionService.class);
        when(selection.select(anyString(), anyList())).thenAnswer(call ->
                new EvidenceSelection(EvidenceStatus.SUPPORTED, call.getArgument(1), "VERIFIED"));
        GroundedKnowledgeService grounded = new GroundedKnowledgeService(search, planner, webSearch, selection);
        orchestrator = new AgentOrchestrator(planner, grounded, mock(PracticeAgentService.class),
                new RagPromptBuilder(3000), llm, context, new AgentRunStore(), new SessionScope(), mistakeReview);
    }

    @Test
    void noEvidenceTriggersSecondSearchThenStopsWithoutCallingModel() {
        RunRequest request = new RunRequest("请问缓存击穿是什么", null, "java", "java",
                null, null, null, null, false);
        when(planner.decide(request)).thenReturn(AgentPlanner.Action.SEARCH_KNOWLEDGE);
        when(planner.rewrite(request.task())).thenReturn("缓存击穿");
        when(search.retrieve(anyString(), eq("java"), eq("java")))
                .thenReturn(new RetrievalResult(List.of(), 0, 0, 0, "FALLBACK"));

        RunResult run = orchestrator.run("alice", request);

        assertEquals("NEEDS_SOURCE", run.status());
        assertEquals(List.of("PLAN", "SEARCH_KNOWLEDGE", "REWRITE_AND_SEARCH", "VERIFY_EVIDENCE", "ASK_FOR_SOURCE"),
                run.steps().stream().map(Step::action).toList());
        verify(search).retrieve("缓存击穿", "java", "java");
        verifyNoInteractions(llm);
        verifyNoInteractions(webSearch);
        assertThrows(SecurityException.class, () -> orchestrator.get("reader", run.runId()));
    }

    @Test
    void evidenceBuildsPromptAndKeepsScopedSession() {
        RunRequest request = request("解释缓存击穿");
        Citation citation = new Citation("c1", "d1", "Redis资料", "热点 key 失效时请求会打到数据库", "java", 0, 1, "BM25");
        when(planner.decide(request)).thenReturn(AgentPlanner.Action.SEARCH_KNOWLEDGE);
        when(search.retrieve(request.task(), "java", "java"))
                .thenReturn(new RetrievalResult(List.of(citation), 0, 1, 1, "BM25"));
        when(llm.chat(anyString(), anyString())).thenReturn(Mono.just("热点键失效可能导致大量请求穿透。[1]"));

        RunResult run = orchestrator.run("alice", request);

        assertEquals("COMPLETED", run.status());
        assertEquals(1, run.citations().size());
        assertTrue(run.sessionId().startsWith("alice:java:"));
        assertEquals("CHECK_CITATIONS", run.steps().get(run.steps().size() - 1).action());
        verify(context).append(run.sessionId(), "ASSISTANT", run.answer());
        assertThrows(SecurityException.class, () -> orchestrator.run("reader",
                new RunRequest("解释缓存击穿", run.sessionId(), "java", "java", null, null, null, null)));
    }

    @Test
    void citationOutsideRetrievedRangeRequiresReview() {
        RunRequest request = request("解释缓存击穿");
        Citation citation = new Citation("c1", "d1", "Redis资料", "热点 key 失效时请求会打到数据库", "java", 0, 1, "BM25");
        when(planner.decide(request)).thenReturn(AgentPlanner.Action.SEARCH_KNOWLEDGE);
        when(search.retrieve(request.task(), "java", "java"))
                .thenReturn(new RetrievalResult(List.of(citation), 0, 1, 1, "BM25"));
        when(llm.chat(anyString(), anyString())).thenReturn(Mono.just("这是没有对应片段的引用。[2]"));

        RunResult run = orchestrator.run("alice", request);

        assertEquals("REVIEW_REQUIRED", run.status());
        assertTrue(run.steps().get(run.steps().size() - 1).observation().contains("编号越界"));
    }

    @Test
    void defaultWebSearchUsesPublicSourceAfterPrivateSearchMisses() {
        RunRequest request = request("解释最新 Java 功能");
        Citation web = new Citation("web-1", "https://example.org/java", "公开文档", "Java 功能说明", "java",
                0, 0.9, "WEB_SEARCH", "https://example.org/java");
        when(planner.decide(request)).thenReturn(AgentPlanner.Action.SEARCH_KNOWLEDGE);
        when(planner.rewrite(request.task())).thenReturn("Java 最新功能");
        when(search.retrieve(anyString(), eq("java"), eq("java")))
                .thenReturn(new RetrievalResult(List.of(), 0, 0, 0, "EMPTY"));
        when(webSearch.search(request.task(), "java")).thenReturn(List.of(web));
        when(llm.chat(anyString(), anyString())).thenReturn(Mono.just("公开资料说明了该功能。[1]"));

        RunResult run = orchestrator.run("alice", request);

        assertEquals("COMPLETED", run.status());
        assertEquals("https://example.org/java", run.citations().get(0).url());
        assertTrue(run.steps().stream().anyMatch(step -> "SEARCH_PUBLIC_WEB".equals(step.action())));
    }

    @Test
    void privateIndexFailureDoesNotTriggerPublicSearch() {
        RunRequest request = request("解释 Redis 缓存击穿");
        when(planner.decide(request)).thenReturn(AgentPlanner.Action.SEARCH_KNOWLEDGE);
        when(search.retrieve(request.task(), "java", "java"))
                .thenReturn(new RetrievalResult(List.of(), 0, 0, 0, "PRIVATE_INDEX_UNAVAILABLE"));

        RunResult run = orchestrator.run("alice", request);

        assertEquals("RETRIEVAL_UNAVAILABLE", run.status());
        verifyNoInteractions(webSearch, llm);
    }

    @Test
    void weakNonemptyCandidatesStopWithoutGenerating() {
        RunRequest request = new RunRequest("空调制冷剂", null, "java", "java", null, null, null, null, false);
        Citation weak = new Citation("c1", "d1", "Java", "HashMap 哈希桶", "java", 0, 1, "BM25");
        when(planner.decide(request)).thenReturn(AgentPlanner.Action.SEARCH_KNOWLEDGE);
        when(search.retrieve(anyString(), anyString(), anyString()))
                .thenReturn(new RetrievalResult(List.of(weak), 8, 20, 1, "KNN+BM25+RRF"));
        when(selection.select(anyString(), anyList()))
                .thenReturn(new EvidenceSelection(EvidenceStatus.INSUFFICIENT, List.of(), "NO_SUPPORT"));
        RunResult run = orchestrator.run("alice", request);
        assertEquals("NEEDS_SOURCE", run.status());
        assertTrue(run.citations().isEmpty());
        assertEquals(1, run.evidence().attempts().get(0).candidates().size());
        verifyNoInteractions(llm, webSearch);
    }

    @Test
    void verificationFailureIsExposedAndDoesNotGenerateOrSearchWeb() {
        RunRequest request = request("解释索引");
        Citation candidate = new Citation("c1", "d1", "MySQL", "索引", "java", 0, 1, "BM25");
        when(planner.decide(request)).thenReturn(AgentPlanner.Action.SEARCH_KNOWLEDGE);
        when(search.retrieve(anyString(), anyString(), anyString()))
                .thenReturn(new RetrievalResult(List.of(candidate), 0, 1, 1, "BM25"));
        when(selection.select(anyString(), anyList()))
                .thenReturn(new EvidenceSelection(EvidenceStatus.ERROR, List.of(), "MODEL_TIMEOUT"));
        RunResult run = orchestrator.run("alice", request);
        assertEquals("EVIDENCE_VERIFICATION_UNAVAILABLE", run.status());
        assertEquals(EvidenceStatus.ERROR, run.evidence().status());
        verifyNoInteractions(llm, webSearch);
        verify(planner, never()).rewrite(anyString());
    }

    @Test
    void rewriteWebVerificationAndGenerationFitTheEightStepBound() {
        RunRequest request = request("新技术");
        Citation weak = new Citation("c1", "d1", "Java", "旧内容", "java", 0, 1, "BM25");
        Citation publicSource = new Citation("web-1", "https://example.org", "新技术", "直接依据", "java", 0, 1,
                "WEB_SEARCH", "https://example.org");
        when(planner.decide(request)).thenReturn(AgentPlanner.Action.SEARCH_KNOWLEDGE);
        when(planner.rewrite(request.task())).thenReturn("新技术原理");
        when(search.retrieve(anyString(), anyString(), anyString()))
                .thenReturn(new RetrievalResult(List.of(weak), 8, 20, 1, "KNN+BM25+RRF"));
        when(selection.select(request.task(), List.of(weak)))
                .thenReturn(new EvidenceSelection(EvidenceStatus.INSUFFICIENT, List.of(), "NO_SUPPORT"));
        when(webSearch.search(request.task(), "java")).thenReturn(List.of(publicSource));
        when(llm.chat(anyString(), anyString())).thenReturn(Mono.just("有直接依据。[1]"));
        RunResult run = orchestrator.run("alice", request);
        assertEquals("COMPLETED", run.status());
        assertEquals(8, run.steps().size());
        assertEquals(3, run.evidence().attempts().size());
    }

    @Test
    void mistakeReviewRunsSkillAndReturnsFocusedQuestion() {
        RunRequest request = new RunRequest("复盘错题", null, "java", "java", "practice-1", null, null, null);
        var question = new com.lm.codeprep.practice.PracticeModels.Question("q2", "practice-2", "解释缓存击穿", "OPEN_ENDED", 1,
                List.of("先说原理"), List.of("chunk-1"));
        Citation citation = new Citation("chunk-1", "doc-1", "Redis", "缓存击穿涉及热点键失效", "java", 0, 1, "BM25");
        when(planner.decide(request)).thenReturn(AgentPlanner.Action.REVIEW_MISTAKES);
        when(mistakeReview.run("alice", "practice-1", "java", "java"))
                .thenReturn(new MistakeReviewSkill.Outcome("COMPLETED", "复盘说明 [1]", List.of("缓存击穿"),
                        List.of(citation), question));

        RunResult run = orchestrator.run("alice", request);

        assertEquals("REVIEW_MISTAKES", run.intent());
        assertEquals(MistakeReviewSkill.ID, run.skill().skillId());
        assertEquals("practice-2", run.skill().reviewPracticeSessionId());
        assertEquals(question, run.question());
        assertEquals(1, run.citations().size());
        verify(mistakeReview).run("alice", "practice-1", "java", "java");
    }

    private RunRequest request(String task) {
        return new RunRequest(task, null, "java", "java", null, null, null, null);
    }
}
