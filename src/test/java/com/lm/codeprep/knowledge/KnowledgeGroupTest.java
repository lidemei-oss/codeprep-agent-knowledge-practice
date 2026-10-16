package com.lm.codeprep.knowledge;

import com.lm.codeprep.infra.ElasticsearchKnowledgeGateway;
import com.lm.codeprep.infra.QwenEmbeddingClient;
import com.lm.codeprep.knowledge.KnowledgeModels.Chunk;
import com.lm.codeprep.knowledge.KnowledgeModels.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeGroupTest {
    @Test
    void groupsCanMoveExistingDocuments() {
        KnowledgeStore store = new KnowledgeStore();
        store.create("d1", "Redis", "java", "java", "redis.md", "缓存");
        store.create("d2", "Spring", "java", "java", "spring.md", null);
        store.markReady("d1", 1);
        store.markReady("d2", 1);

        assertEquals("缓存", store.requiredDocument("d1").groupName());
        assertEquals("默认分组", store.requiredDocument("d2").groupName());
        assertEquals(Set.of("d1"), store.readyDocumentIds("java", "java", "缓存"));
        store.moveGroup("d2", "缓存");
        assertEquals(2, store.groups().get(0).readyCount());
    }

    @Test
    void practiceRetrievalNeverFallsBackToAnotherGroup() {
        KnowledgeStore store = new KnowledgeStore();
        store.create("d1", "Redis", "java", "java", "redis.md", "缓存");
        store.create("d2", "Spring", "java", "java", "spring.md", "框架");
        store.saveChunk(new Chunk("c1", "d1", "Redis", "Java Redis 缓存击穿", "java", "java", 0, null));
        store.saveChunk(new Chunk("c2", "d2", "Spring", "Java Spring 缓存击穿", "java", "java", 0, null));
        store.markReady("d1", 1);
        store.markReady("d2", 1);
        ElasticsearchKnowledgeGateway es = mock(ElasticsearchKnowledgeGateway.class);
        QwenEmbeddingClient embedding = mock(QwenEmbeddingClient.class);
        when(es.enabled()).thenReturn(true);
        when(es.hybridSearch("缓存击穿", "java", "java", List.of(), Set.of("d1")))
                .thenReturn(new RetrievalResult(List.of(), 0, 0, 0, "EMPTY"));
        KnowledgeSearchService search = new KnowledgeSearchService(store, es, embedding);

        assertEquals(List.of("c1"), search.retrieve("缓存击穿", "java", "java", "缓存")
                .citations().stream().map(c -> c.chunkId()).toList());
        assertEquals(List.of("c2"), search.samples("java", "java", "框架", 3)
                .stream().map(c -> c.chunkId()).toList());
        assertTrue(search.retrieve("缓存击穿", "java", "java", "不存在").citations().isEmpty());
        verify(es).hybridSearch("缓存击穿", "java", "java", List.of(), Set.of("d1"));
    }
}
