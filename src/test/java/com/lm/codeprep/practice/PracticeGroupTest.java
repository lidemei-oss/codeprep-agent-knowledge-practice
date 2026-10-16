package com.lm.codeprep.practice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.infra.ElasticsearchKnowledgeGateway;
import com.lm.codeprep.infra.QwenEmbeddingClient;
import com.lm.codeprep.knowledge.KnowledgeModels.Chunk;
import com.lm.codeprep.knowledge.KnowledgeSearchService;
import com.lm.codeprep.knowledge.KnowledgeStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PracticeGroupTest {
    @Test
    void selectedGroupControlsQuestionSourcesAndReviewScope() {
        KnowledgeStore store = new KnowledgeStore();
        store.create("d1", "Redis", "java", "java", "redis.md", "缓存");
        store.create("d2", "Spring", "java", "java", "spring.md", "框架");
        store.saveChunk(new Chunk("c1", "d1", "Redis", "Java Redis 缓存原理", "java", "java", 0, null));
        store.saveChunk(new Chunk("c2", "d2", "Spring", "Java Spring 原理", "java", "java", 0, null));
        store.markReady("d1", 1);
        store.markReady("d2", 1);
        KnowledgeSearchService search = new KnowledgeSearchService(store, mock(ElasticsearchKnowledgeGateway.class),
                mock(QwenEmbeddingClient.class));
        PracticeAgentService practice = new PracticeAgentService(search, mock(DeepSeekClient.class), new ObjectMapper());

        var first = practice.start("alice", new PracticeModels.StartRequest("java", "java", 1, "medium", "缓存"));
        assertEquals(List.of("c1"), first.sourceChunkIds());
        practice.answer("alice", first.sessionId(), new PracticeModels.AnswerRequest(first.id(), "req-1", "不知道"));
        assertEquals("缓存", practice.reviewData("alice", first.sessionId()).groupName());
        assertThrows(IllegalStateException.class, () -> practice.start("alice",
                new PracticeModels.StartRequest("java", "java", 1, "medium", "空组")));
    }
}
