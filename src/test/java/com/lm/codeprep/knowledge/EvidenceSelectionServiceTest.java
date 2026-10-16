package com.lm.codeprep.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.infra.DeepSeekClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.lm.codeprep.knowledge.KnowledgeModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvidenceSelectionServiceTest {
    private final DeepSeekClient llm = mock(DeepSeekClient.class);
    private final Citation unrelated = citation("c1", "覆盖索引减少回表");
    private final Citation relevant = citation("c2", "联合索引最左前缀按 tenant_id、status、created_at 排序");

    @Test
    void onlySelectedEvidenceEntersPromptInModelOrder() {
        when(llm.enabled()).thenReturn(true);
        when(llm.chatJson(anyString(), anyString())).thenReturn(Mono.just(
                "{\"status\":\"SUPPORTED\",\"selectedChunkIds\":[\"c2\"]}"));
        var result = service(30000, 12000).select("联合索引最左前缀", List.of(unrelated, relevant));
        assertEquals(EvidenceStatus.SUPPORTED, result.status());
        assertEquals(List.of(relevant), result.citations());
        String prompt = new RagPromptBuilder(12000).userPrompt("联合索引", List.of(), result.citations());
        assertTrue(prompt.contains("最左前缀"));
        assertFalse(prompt.contains("覆盖索引"));
    }

    @Test
    void nonemptyButIrrelevantCandidatesCanBeInsufficient() {
        when(llm.enabled()).thenReturn(true);
        when(llm.chatJson(anyString(), anyString())).thenReturn(Mono.just(
                "{\"status\":\"INSUFFICIENT\",\"selectedChunkIds\":[]}"));
        assertEquals(EvidenceStatus.INSUFFICIENT,
                service(30000, 12000).select("空调制冷剂", List.of(relevant)).status());
    }

    @Test
    void foreignDuplicateOrContradictoryIdsAreErrorsNotMisses() {
        when(llm.enabled()).thenReturn(true);
        for (String response : List.of(
                "{\"status\":\"SUPPORTED\",\"selectedChunkIds\":[\"foreign-secret\"]}",
                "{\"status\":\"SUPPORTED\",\"selectedChunkIds\":[\"c2\",\"c2\"]}",
                "{\"status\":\"SUPPORTED\",\"selectedChunkIds\":[]}",
                "{\"status\":\"INSUFFICIENT\",\"selectedChunkIds\":[\"c2\"]}",
                "not JSON",
                "{\"status\":\"SUPPORTED\",\"selectedChunkIds\":[\"c2\"]} {}")) {
            when(llm.chatJson(anyString(), anyString())).thenReturn(Mono.just(response));
            var result = service(30000, 12000).select("索引", List.of(relevant));
            assertEquals(EvidenceStatus.ERROR, result.status());
            assertTrue(result.citations().isEmpty());
        }
    }

    @Test
    void disabledAndTimedOutModelsFailClosed() {
        assertEquals("MODEL_DISABLED", service(30000, 12000).select("索引", List.of(relevant)).reason());
        when(llm.enabled()).thenReturn(true);
        when(llm.chatJson(anyString(), anyString())).thenReturn(Mono.never());
        assertEquals("MODEL_TIMEOUT", service(20, 12000).select("索引", List.of(relevant)).reason());
    }

    @Test
    void doesNotSilentlyDiscardRequiredEvidenceToFitPrompt() {
        when(llm.enabled()).thenReturn(true);
        when(llm.chatJson(anyString(), anyString())).thenReturn(Mono.just(
                "{\"status\":\"SUPPORTED\",\"selectedChunkIds\":[\"c2\"]}"));
        assertEquals("PROMPT_BUDGET_EXCEEDED", service(30000, 5).select("索引", List.of(relevant)).reason());
    }

    @Test
    void emptyCandidatesDoNotCallModel() {
        assertEquals(EvidenceStatus.INSUFFICIENT, service(30000, 12000).select("索引", List.of()).status());
        verifyNoInteractions(llm);
    }

    private EvidenceSelectionService service(long timeoutMs, int budget) {
        return new EvidenceSelectionService(llm, new ObjectMapper(), new RagPromptBuilder(budget), timeoutMs, 5, 20000);
    }

    private Citation citation(String id, String text) {
        return new Citation(id, "document", "资料", text, "java", 0, 1, "BM25");
    }
}
