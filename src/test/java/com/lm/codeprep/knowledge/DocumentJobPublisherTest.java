package com.lm.codeprep.knowledge;

import com.lm.codeprep.persistence.entity.DocumentOutboxEntity;
import com.lm.codeprep.persistence.mapper.DocumentOutboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentJobPublisherTest {
    @Test
    @SuppressWarnings("unchecked")
    void failedRabbitPublishKeepsDocumentProcessingAndSchedulesRetry() {
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-1", "Redis", "java", "java", "redis.txt");
        DocumentOutboxMapper outbox = mock(DocumentOutboxMapper.class);
        DocumentOutboxEntity event = new DocumentOutboxEntity();
        event.documentId = "doc-1";
        event.attemptCount = 0;
        when(outbox.due(anyLong(), eq(20))).thenReturn(List.of(event));
        when(outbox.claim(eq("doc-1"), anyString(), anyLong(), anyLong())).thenReturn(1);
        ObjectProvider<RabbitTemplate> provider = mock(ObjectProvider.class);
        RabbitTemplate template = mock(RabbitTemplate.class);
        when(provider.getIfAvailable()).thenReturn(template);
        doThrow(new IllegalStateException("broker unavailable")).when(template)
                .convertAndSend(eq("exchange"), eq("jobs"), eq("doc-1"), any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        DocumentJobPublisher publisher = new DocumentJobPublisher(outbox, store, provider,
                "exchange", "jobs", 30000, 1000, 300000);

        publisher.dispatchDue();

        assertEquals("PROCESSING", store.requiredDocument("doc-1").status());
        verify(outbox).retry(eq("doc-1"), anyString(), anyLong(), contains("broker unavailable"), anyLong());
        verify(outbox, never()).sent(anyString(), anyString(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void terminalDocumentCancelsAnAmbiguousDuplicatePublish() {
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-2", "Redis", "java", "java", "redis.txt");
        store.markReady("doc-2", 1);
        DocumentOutboxMapper outbox = mock(DocumentOutboxMapper.class);
        DocumentOutboxEntity event = new DocumentOutboxEntity();
        event.documentId = "doc-2";
        when(outbox.due(anyLong(), eq(20))).thenReturn(List.of(event));
        when(outbox.claim(eq("doc-2"), anyString(), anyLong(), anyLong())).thenReturn(1);
        ObjectProvider<RabbitTemplate> provider = mock(ObjectProvider.class);
        DocumentJobPublisher publisher = new DocumentJobPublisher(outbox, store, provider,
                "exchange", "jobs", 30000, 1000, 300000);

        publisher.dispatchDue();

        verify(outbox).cancel(eq("doc-2"), anyString(), anyLong());
        verifyNoInteractions(provider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmedPublishMarksOutboxSentWithoutWaitingForIngestion() {
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-3", "Redis", "java", "java", "redis.txt");
        DocumentOutboxMapper outbox = mock(DocumentOutboxMapper.class);
        DocumentOutboxEntity event = new DocumentOutboxEntity();
        event.documentId = "doc-3";
        when(outbox.due(anyLong(), eq(20))).thenReturn(List.of(event));
        when(outbox.claim(eq("doc-3"), anyString(), anyLong(), anyLong())).thenReturn(1);
        when(outbox.sent(eq("doc-3"), anyString(), anyLong())).thenReturn(1);
        ObjectProvider<RabbitTemplate> provider = mock(ObjectProvider.class);
        RabbitTemplate template = mock(RabbitTemplate.class);
        when(provider.getIfAvailable()).thenReturn(template);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).convertAndSend(eq("exchange"), eq("jobs"), eq("doc-3"), any(CorrelationData.class));
        DocumentJobPublisher publisher = new DocumentJobPublisher(outbox, store, provider,
                "exchange", "jobs", 30000, 1000, 300000);

        publisher.dispatchDue();

        verify(outbox).sent(eq("doc-3"), anyString(), anyLong());
        verify(outbox, never()).retry(anyString(), anyString(), anyLong(), anyString(), anyLong());
        assertEquals("PROCESSING", store.requiredDocument("doc-3").status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void brokerNackLeavesDocumentProcessingForLaterRetry() {
        KnowledgeStore store = new KnowledgeStore();
        store.create("doc-4", "Redis", "java", "java", "redis.txt");
        DocumentOutboxMapper outbox = mock(DocumentOutboxMapper.class);
        DocumentOutboxEntity event = new DocumentOutboxEntity();
        event.documentId = "doc-4";
        when(outbox.due(anyLong(), eq(20))).thenReturn(List.of(event));
        when(outbox.claim(eq("doc-4"), anyString(), anyLong(), anyLong())).thenReturn(1);
        ObjectProvider<RabbitTemplate> provider = mock(ObjectProvider.class);
        RabbitTemplate template = mock(RabbitTemplate.class);
        when(provider.getIfAvailable()).thenReturn(template);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker nack"));
            return null;
        }).when(template).convertAndSend(eq("exchange"), eq("jobs"), eq("doc-4"), any(CorrelationData.class));

        new DocumentJobPublisher(outbox, store, provider, "exchange", "jobs", 30000, 1000, 300000)
                .dispatchDue();

        verify(outbox).retry(eq("doc-4"), anyString(), anyLong(), anyString(), anyLong());
        verify(outbox, never()).sent(anyString(), anyString(), anyLong());
        assertEquals("PROCESSING", store.requiredDocument("doc-4").status());
    }
}
