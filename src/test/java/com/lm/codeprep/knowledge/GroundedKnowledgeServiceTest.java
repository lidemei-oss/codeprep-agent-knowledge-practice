package com.lm.codeprep.knowledge;

import com.lm.codeprep.agent.AgentPlanner;
import com.lm.codeprep.infra.WebSearchClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.lm.codeprep.knowledge.KnowledgeModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroundedKnowledgeServiceTest {
    private final KnowledgeSearchService search = mock(KnowledgeSearchService.class);
    private final AgentPlanner planner = mock(AgentPlanner.class);
    private final WebSearchClient web = mock(WebSearchClient.class);
    private final EvidenceSelectionService selection = mock(EvidenceSelectionService.class);
    private final GroundedKnowledgeService service = new GroundedKnowledgeService(search, planner, web, selection);
    private final Citation weak = citation("weak", "覆盖索引");
    private final Citation correct = citation("correct", "联合索引最左前缀");

    @Test
    void weakNonemptyResultsTriggerRewriteAndRetainRawCandidates() {
        when(search.retrieve("原问题", "java", "java")).thenReturn(raw(List.of(weak)));
        when(planner.rewrite("原问题")).thenReturn("最左前缀");
        when(search.retrieve("最左前缀", "java", "java")).thenReturn(raw(List.of(weak, correct)));
        when(selection.select("原问题", List.of(weak))).thenReturn(insufficient());
        when(selection.select("原问题", List.of(weak, correct)))
                .thenReturn(new EvidenceSelection(EvidenceStatus.SUPPORTED, List.of(correct), "VERIFIED"));
        var result = service.retrieve("原问题", "java", "java", true);
        assertEquals(List.of(correct), result.citations());
        assertEquals(2, result.rerankedHits());
        assertEquals(2, result.evidence().attempts().size());
        assertEquals(List.of(weak), result.evidence().attempts().get(0).candidates());
        verify(selection, never()).select(eq("最左前缀"), anyList());
        verifyNoInteractions(web);
    }

    @Test
    void verificationFaultDoesNotRewriteSearchWebOrExposeCandidatesAsEvidence() {
        when(search.retrieve(anyString(), anyString(), anyString())).thenReturn(raw(List.of(weak)));
        when(selection.select(anyString(), anyList()))
                .thenReturn(new EvidenceSelection(EvidenceStatus.ERROR, List.of(), "MODEL_TIMEOUT"));
        var result = service.retrieve("问题", "java", "java", true);
        assertEquals("EVIDENCE_VERIFICATION_UNAVAILABLE", result.strategy());
        assertTrue(result.citations().isEmpty());
        assertEquals(1, result.evidence().attempts().get(0).candidates().size());
        verifyNoInteractions(planner, web);
    }

    @Test
    void irrelevantPublicResultsAreNotUsedForGeneration() {
        when(search.retrieve(anyString(), anyString(), anyString())).thenReturn(raw(List.of(weak)));
        when(selection.select(anyString(), anyList())).thenReturn(insufficient());
        when(web.search("空调", "java")).thenReturn(List.of(weak));
        var result = service.retrieve("空调", "java", "java", true);
        assertEquals("NEEDS_SOURCE", result.strategy());
        assertEquals(EvidenceStatus.INSUFFICIENT, result.evidence().status());
        assertTrue(result.citations().isEmpty());
        assertEquals("WEB_SEARCH", result.evidence().attempts().get(1).strategy());
    }

    @Test
    void retrievalFaultIsNotEvidenceInsufficiency() {
        when(search.retrieve(anyString(), anyString(), anyString()))
                .thenReturn(new RetrievalResult(List.of(), 0, 0, 0, "PRIVATE_INDEX_UNAVAILABLE"));
        var result = service.retrieve("问题", "java", "java", true);
        assertEquals("PRIVATE_INDEX_UNAVAILABLE", result.strategy());
        assertEquals(EvidenceStatus.ERROR, result.evidence().status());
        verifyNoInteractions(selection, planner, web);
    }

    @Test
    void webOptOutRemainsEffectiveEvenWhenPrivateCandidatesExist() {
        when(search.retrieve(anyString(), anyString(), anyString())).thenReturn(raw(List.of(weak)));
        when(selection.select(anyString(), anyList())).thenReturn(insufficient());
        var result = service.retrieve("问题", "java", "java", false);
        assertEquals("WEB_SEARCH_SKIPPED", result.strategy());
        verifyNoInteractions(web);
    }

    @Test
    void esFaultWithWeakMysqlFallbackDoesNotBecomeAPublicSearchMiss() {
        when(search.retrieve(anyString(), anyString(), anyString())).thenReturn(
                new RetrievalResult(List.of(weak), 0, 1, 1, "MYSQL_KEYWORD", null, true));
        when(selection.select(anyString(), anyList())).thenReturn(insufficient());
        var result = service.retrieve("问题", "java", "java", true);
        assertEquals("PRIVATE_INDEX_UNAVAILABLE", result.strategy());
        assertEquals(EvidenceStatus.ERROR, result.evidence().status());
        assertTrue(result.evidence().attempts().get(0).privateIndexUnavailable());
        verifyNoInteractions(planner, web);
    }

    @Test
    void esFaultCanStillUseVerifiedMysqlEvidence() {
        when(search.retrieve(anyString(), anyString(), anyString())).thenReturn(
                new RetrievalResult(List.of(correct), 0, 1, 1, "MYSQL_KEYWORD", null, true));
        when(selection.select(anyString(), anyList())).thenReturn(
                new EvidenceSelection(EvidenceStatus.SUPPORTED, List.of(correct), "VERIFIED"));
        var result = service.retrieve("问题", "java", "java", true);
        assertEquals(List.of(correct), result.citations());
        assertEquals(EvidenceStatus.SUPPORTED, result.evidence().status());
        assertTrue(result.privateIndexUnavailable());
        verifyNoInteractions(planner, web);
    }

    @Test
    void recentHistoryClarifiesIntentButDoesNotReplaceCandidateEvidence() {
        List<String> history = List.of("USER: 联合索引的最左前缀是什么");
        when(search.retrieve(anyString(), anyString(), anyString())).thenReturn(raw(List.of(correct)));
        when(selection.select("它为什么有这个限制", List.of(correct), history)).thenReturn(
                new EvidenceSelection(EvidenceStatus.SUPPORTED, List.of(correct), "VERIFIED"));
        var result = service.retrieve("它为什么有这个限制", "java", "java", false, history);
        assertEquals(List.of(correct), result.citations());
        verify(selection).select("它为什么有这个限制", List.of(correct), history);
        verifyNoInteractions(web);
    }

    private RetrievalResult raw(List<Citation> candidates) {
        return new RetrievalResult(candidates, 8, 20, candidates.size(), "KNN+BM25+RRF");
    }
    private EvidenceSelection insufficient() {
        return new EvidenceSelection(EvidenceStatus.INSUFFICIENT, List.of(), "NOT_SUPPORTED");
    }
    private Citation citation(String id, String text) {
        return new Citation(id, "document", "资料", text, "java", 0, 1, "BM25");
    }
}
