package com.lm.codeprep.knowledge;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class DocumentJobConsumerTest {
    @Test
    void retriesThenAcknowledgesSuccessfulDocument() throws Exception {
        DocumentIngestionService ingestion = mock(DocumentIngestionService.class);
        DocumentUploadService uploads = mock(DocumentUploadService.class);
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-1", "Redis", "java", "java", "redis.txt");
        Channel channel = mock(Channel.class);
        Path path = Path.of("redis.txt");
        when(uploads.pathOf("doc-1")).thenReturn(path);
        doThrow(new IllegalStateException("temporary")).doNothing()
                .when(ingestion).processOrThrow(eq("doc-1"), eq(path), anyString());

        new DocumentJobConsumer(ingestion, uploads, store, 3).consume("doc-1", channel, 7L);

        verify(ingestion, times(2)).processOrThrow(eq("doc-1"), eq(path), anyString());
        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void exhaustedAttemptsRejectsToDeadLetterQueueAndMarksFailed() throws Exception {
        DocumentIngestionService ingestion = mock(DocumentIngestionService.class);
        DocumentUploadService uploads = mock(DocumentUploadService.class);
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-1", "Redis", "java", "java", "redis.txt");
        Channel channel = mock(Channel.class);
        Path path = Path.of("redis.txt");
        when(uploads.pathOf("doc-1")).thenReturn(path);
        doThrow(new IllegalStateException("broken")).when(ingestion)
                .processOrThrow(eq("doc-1"), eq(path), anyString());

        new DocumentJobConsumer(ingestion, uploads, store, 3).consume("doc-1", channel, 7L);

        verify(ingestion, times(3)).processOrThrow(eq("doc-1"), eq(path), anyString());
        verify(channel).basicNack(7L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        assertEquals("FAILED", store.requiredDocument("doc-1").status());
    }

    @Test
    void duplicateDeliveryCannotReachCleanupWhenAnotherConsumerOwnsDocument() throws Exception {
        DocumentIngestionService ingestion = mock(DocumentIngestionService.class);
        DocumentUploadService uploads = mock(DocumentUploadService.class);
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-1", "Redis", "java", "java", "redis.txt");
        store.claimIngestion("doc-1", "first-consumer");
        Channel channel = mock(Channel.class);

        new DocumentJobConsumer(ingestion, uploads, store, 3).consume("doc-1", channel, 7L);

        verifyNoInteractions(ingestion, uploads);
        verify(channel).basicAck(7L, false);
        assertEquals("PROCESSING", store.requiredDocument("doc-1").status());
    }
}
