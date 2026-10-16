package com.lm.codeprep.knowledge;

import com.lm.codeprep.infra.ElasticsearchKnowledgeGateway;
import com.lm.codeprep.infra.QwenEmbeddingClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeSearchServiceTest {
    @Test
    void distinguishesIndexFailureFromARealMiss() {
        ElasticsearchKnowledgeGateway es = mock(ElasticsearchKnowledgeGateway.class);
        when(es.enabled()).thenReturn(true);
        when(es.hybridSearch(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("ES unavailable"));
        QwenEmbeddingClient embedding = mock(QwenEmbeddingClient.class);
        when(embedding.embed(anyString())).thenReturn(java.util.List.of(1.0));
        KnowledgeSearchService service = new KnowledgeSearchService(new KnowledgeStore(), es, embedding);

        var result = service.retrieve("Redis 缓存击穿", "java", "java");

        assertTrue(result.citations().isEmpty());
        assertEquals("PRIVATE_INDEX_UNAVAILABLE", result.strategy());
    }
}
