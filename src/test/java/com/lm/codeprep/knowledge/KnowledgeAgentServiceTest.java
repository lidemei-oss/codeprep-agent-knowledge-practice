package com.lm.codeprep.knowledge;

import com.lm.codeprep.auth.SessionScope;
import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.infra.RedisContextStore;
import com.lm.codeprep.infra.WebSearchClient;
import com.lm.codeprep.agent.AgentPlanner;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static com.lm.codeprep.knowledge.KnowledgeModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeAgentServiceTest {
    @Test
    void noEvidenceDoesNotInvokeModelInEitherResponseMode() {
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        when(search.retrieve(anyString(), eq("java"), eq("java")))
                .thenReturn(new RetrievalResult(List.of(), 0, 0, 0, "IN_MEMORY_KEYWORD"));
        DeepSeekClient llm = mock(DeepSeekClient.class);
        when(llm.enabled()).thenReturn(true);
        RedisContextStore context = mock(RedisContextStore.class);
        when(context.allow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        when(context.read(anyString())).thenReturn(List.of());
        AgentPlanner planner = mock(AgentPlanner.class);
        WebSearchClient webSearch = mock(WebSearchClient.class);
        GroundedKnowledgeService grounded = new GroundedKnowledgeService(search, planner, webSearch,
                mock(EvidenceSelectionService.class));
        KnowledgeAgentService service = new KnowledgeAgentService(grounded, new RagPromptBuilder(3000),
                llm, context, new SessionScope());

        RagResponse response = service.ask("alice", null, "未知主题", "java", "java", true);
        List<AgentEvent> events = service.stream("alice", null, "未知主题", "java", "java", true).collectList().block();

        assertTrue(response.answer().contains("没有足够证据"));
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event -> "delta".equals(event.type()) && event.content().contains("没有足够证据")));
        verify(llm, never()).chat(anyString(), anyString());
        verify(llm, never()).stream(anyString(), anyString());
        verify(webSearch, times(2)).search("未知主题", "java");
    }

    @Test
    void directQuestionUsesPublicSourceByDefaultWhenPrivateSearchMisses() {
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        when(search.retrieve(anyString(), eq("java"), eq("java")))
                .thenReturn(new RetrievalResult(List.of(), 0, 0, 0, "EMPTY"));
        DeepSeekClient llm = mock(DeepSeekClient.class);
        when(llm.chat(anyString(), anyString())).thenReturn(Mono.just("公开资料的回答。[1]"));
        RedisContextStore context = mock(RedisContextStore.class);
        when(context.allow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        when(context.read(anyString())).thenReturn(List.of());
        AgentPlanner planner = mock(AgentPlanner.class);
        WebSearchClient webSearch = mock(WebSearchClient.class);
        Citation source = new Citation("web-1", "https://example.org/java", "公开文档", "Java 功能说明",
                "java", 0, 0.9, "WEB_SEARCH", "https://example.org/java");
        when(webSearch.search("Java 新功能", "java")).thenReturn(List.of(source));
        EvidenceSelectionService selection = mock(EvidenceSelectionService.class);
        when(selection.select("Java 新功能", List.of(source)))
                .thenReturn(new EvidenceSelection(EvidenceStatus.SUPPORTED, List.of(source), "VERIFIED"));
        KnowledgeAgentService service = new KnowledgeAgentService(new GroundedKnowledgeService(search, planner, webSearch, selection),
                new RagPromptBuilder(3000), llm, context, new SessionScope());

        RagResponse result = service.ask("alice", null, "Java 新功能", "java", "java", true);

        assertEquals("WEB_SEARCH", result.retrieval().strategy());
        assertEquals("https://example.org/java", result.citations().get(0).url());
        assertTrue(result.promptPreview().contains("https://example.org/java"));
        verify(webSearch).search("Java 新功能", "java");
    }

    @Test
    void verificationErrorHasTheSameClosedBehaviorForSyncAndStreaming() {
        GroundedKnowledgeService grounded = mock(GroundedKnowledgeService.class);
        Citation raw = new Citation("c1", "d1", "资料", "候选", "java", 0, 1, "BM25");
        EvidenceSelection failure = new EvidenceSelection(EvidenceStatus.ERROR, List.of(), "MODEL_TIMEOUT");
        EvidenceReport report = new EvidenceReport(EvidenceStatus.ERROR, "MODEL_TIMEOUT",
                List.of(new EvidenceAttempt("问题", "BM25", 0, 1, List.of(raw), failure)));
        when(grounded.retrieve(anyString(), anyString(), anyString(), anyBoolean(), anyList())).thenReturn(
                new RetrievalResult(List.of(), 0, 1, 1, "EVIDENCE_VERIFICATION_UNAVAILABLE", report));
        DeepSeekClient llm = mock(DeepSeekClient.class);
        when(llm.enabled()).thenReturn(true);
        RedisContextStore context = mock(RedisContextStore.class);
        when(context.allow(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        when(context.read(anyString())).thenReturn(List.of());
        KnowledgeAgentService service = new KnowledgeAgentService(grounded, new RagPromptBuilder(3000), llm,
                context, new SessionScope());
        RagResponse answer = service.ask("alice", null, "问题", "java", "java", true);
        List<AgentEvent> events = service.stream("alice", null, "问题", "java", "java", true).collectList().block();
        assertTrue(answer.answer().contains("证据筛选暂时不可用"));
        assertEquals(EvidenceStatus.ERROR, answer.retrieval().evidence().status());
        assertEquals(report, events.stream().filter(e -> "retrieval".equals(e.type())).findFirst().orElseThrow().evidence());
        verify(llm, never()).chat(anyString(), anyString());
        verify(llm, never()).stream(anyString(), anyString());
    }
}
