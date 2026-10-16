package com.lm.codeprep.knowledge;

import com.lm.codeprep.infra.ElasticsearchKnowledgeGateway;
import com.lm.codeprep.infra.QwenEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentIngestionServiceTest {
    @TempDir Path temp;

    @Test
    void publishesChunksOnlyAfterAllIndexWritesComplete() throws Exception {
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-1", "Redis", "java", "java", "redis.txt");
        ElasticsearchKnowledgeGateway es = mock(ElasticsearchKnowledgeGateway.class);
        QwenEmbeddingClient embedding = mock(QwenEmbeddingClient.class);
        doAnswer(invocation -> {
            assertEquals("PROCESSING", store.requiredDocument("doc-1").status());
            assertTrue(store.chunks("java", "java").isEmpty());
            return null;
        }).when(es).index(any());
        Path file = temp.resolve("redis.txt");
        Files.writeString(file, "Redis 缓存击穿需要互斥锁或逻辑过期处理。");

        new DocumentIngestionService(store, embedding, es, new StructuredDocumentChunker(700, 160, 80)).process("doc-1", file);

        assertEquals("READY", store.requiredDocument("doc-1").status());
        assertEquals(1, store.chunks("java", "java").size());
        assertEquals("doc-1-0", store.chunks("java", "java").get(0).id());
        verify(es).markDocumentReady("doc-1");
    }

    @Test
    void failedIndexDoesNotLeakPartialChunks() throws Exception {
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-2", "Redis", "java", "java", "redis.txt");
        ElasticsearchKnowledgeGateway es = mock(ElasticsearchKnowledgeGateway.class);
        doThrow(new IllegalStateException("index unavailable")).when(es).index(any());
        Path file = temp.resolve("redis.txt");
        Files.writeString(file, "Redis 缓存击穿需要互斥锁或逻辑过期处理。");

        new DocumentIngestionService(store, mock(QwenEmbeddingClient.class), es,
                new StructuredDocumentChunker(700, 160, 80)).process("doc-2", file);

        assertEquals("FAILED", store.requiredDocument("doc-2").status());
        assertTrue(store.chunks("java", "java").isEmpty());
        verify(es, times(2)).deleteDocument("doc-2");
        verify(es, never()).markDocumentReady("doc-2");
    }

    @Test
    void markdownUploadPreservesHeadingPathAndCodeFence() throws Exception {
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-3", "Java 笔记", "java", "java", "notes.md");
        ElasticsearchKnowledgeGateway es = mock(ElasticsearchKnowledgeGateway.class);
        Path file = temp.resolve("notes.md");
        Files.writeString(file, "# 集合\n## HashMap\n扩容示例：\n\n```java\nMap<String, Integer> map = new HashMap<>();\n```\n");

        new DocumentIngestionService(store, mock(QwenEmbeddingClient.class), es,
                new StructuredDocumentChunker(700, 160, 80)).process("doc-3", file);

        assertEquals("READY", store.requiredDocument("doc-3").status());
        assertTrue(store.chunks("java", "java").get(0).content().contains("章节：集合 > HashMap"));
        assertTrue(store.chunks("java", "java").get(0).content().contains("```java"));
        verify(es).markDocumentReady("doc-3");
    }
}
