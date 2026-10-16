package com.lm.codeprep.knowledge;

import com.lm.codeprep.persistence.entity.DocumentOutboxEntity;
import com.lm.codeprep.persistence.mapper.DocumentMapper;
import com.lm.codeprep.persistence.mapper.DocumentOutboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "codeprep.elasticsearch.enabled=false", "codeprep.embedding.enabled=false",
        "codeprep.llm.enabled=false", "codeprep.redis.enabled=false",
        "codeprep.rabbitmq.enabled=true", "spring.rabbitmq.listener.simple.auto-startup=false",
        "codeprep.rabbitmq.outbox.initial-delay-ms=600000",
        "codeprep.rabbitmq.outbox.reconcile-ms=600000"
})
@ActiveProfiles("test")
class DocumentOutboxPersistenceTest {
    @Autowired DocumentSubmissionService submissions;
    @Autowired DocumentOutboxMapper outbox;
    @Autowired DocumentMapper documents;
    @Autowired KnowledgeStore store;

    @Test
    void acceptingDocumentAtomicallyCreatesPendingOutboxEvent() {
        String id = UUID.randomUUID().toString();
        submissions.accept(id, "Java并发", "java", "java", "threads.pdf", "并发");

        assertEquals("PROCESSING", documents.selectById(id).status);
        DocumentOutboxEntity event = outbox.selectById(id);
        assertNotNull(event);
        assertEquals("PENDING", event.status);
        assertEquals(0, event.attemptCount);
    }

    @Test
    void outboxInsertFailureRollsBackDocumentInsert() {
        String id = UUID.randomUUID().toString();
        DocumentOutboxEntity conflict = new DocumentOutboxEntity();
        conflict.documentId = id;
        conflict.status = "PENDING";
        conflict.attemptCount = 0;
        conflict.nextAttemptAt = System.currentTimeMillis();
        conflict.createdAt = conflict.nextAttemptAt;
        conflict.updatedAt = conflict.nextAttemptAt;
        outbox.insert(conflict);

        assertThrows(RuntimeException.class, () ->
                submissions.accept(id, "Java并发", "java", "java", "threads.pdf", "并发"));
        assertNull(documents.selectById(id));
    }

    @Test
    void expiredLeaseCanBeReclaimedButOldPublisherCannotChangeItsState() {
        String id = UUID.randomUUID().toString();
        store.create(id, "Java并发", "java", "java", "threads.pdf");
        long now = System.currentTimeMillis();
        assertEquals(1, outbox.insertMissing(id, now));
        assertEquals(1, outbox.claim(id, "first", now, now + 100));
        assertEquals(0, outbox.claim(id, "second", now + 50, now + 150));
        assertEquals(1, outbox.claim(id, "second", now + 101, now + 300));
        assertEquals(0, outbox.sent(id, "first", now + 102));
        assertEquals(1, outbox.retry(id, "second", now + 1000, "network down", now + 102));
        assertEquals("PENDING", outbox.selectById(id).status);
        assertEquals(2, outbox.selectById(id).attemptCount);
    }

    @Test
    void committedPendingEventRemainsDiscoverableUntilItIsConfirmed() {
        String id = UUID.randomUUID().toString();
        submissions.accept(id, "Java并发", "java", "java", "threads.pdf", "并发");
        long now = System.currentTimeMillis();
        assertTrue(outbox.due(now, 20).stream().anyMatch(event -> id.equals(event.documentId)));

        assertEquals(1, outbox.claim(id, "publisher", now, now + 30000));
        assertEquals(1, outbox.retry(id, "publisher", now + 5000, "broker unavailable", now));
        assertFalse(outbox.due(now + 4999, 20).stream().anyMatch(event -> id.equals(event.documentId)));
        assertTrue(outbox.due(now + 5000, 20).stream().anyMatch(event -> id.equals(event.documentId)));
        assertEquals("PROCESSING", documents.selectById(id).status);
    }

    @Test
    void onlyOneConcurrentConsumerCanClaimAndOnlyOwnerCanFinish() throws Exception {
        String id = UUID.randomUUID().toString();
        submissions.accept(id, "Java并发", "java", "java", "threads.pdf", "并发");
        long now = System.currentTimeMillis();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                ready.countDown(); start.await();
                return documents.claimIngestion(id, "consumer-a", now, now + 60000);
            });
            var second = pool.submit(() -> {
                ready.countDown(); start.await();
                return documents.claimIngestion(id, "consumer-b", now, now + 60000);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS));
            String winner = documents.ownsIngestion(id, "consumer-a", now + 1) == 1
                    ? "consumer-a" : "consumer-b";
            String loser = winner.equals("consumer-a") ? "consumer-b" : "consumer-a";
            assertEquals(0, documents.markFailedOwned(id, loser, now + 1));
            assertEquals(1, documents.markReadyOwned(id, winner, 2, now + 1));
            assertEquals("READY", documents.selectById(id).status);
            assertEquals(0, documents.claimIngestion(id, loser, now + 2, now + 60000));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void expiredConsumerLeaseCanBeRequeuedAndOldOwnerCannotFinish() {
        String id = UUID.randomUUID().toString();
        submissions.accept(id, "Java并发", "java", "java", "threads.pdf", "并发");
        long now = System.currentTimeMillis();
        assertEquals(1, outbox.claim(id, "publisher", now, now + 1000));
        assertEquals(1, outbox.sent(id, "publisher", now));
        assertEquals(1, documents.claimIngestion(id, "old-consumer", now, now + 100));
        assertTrue(outbox.expiredIngestionClaims(now + 99, 20).isEmpty());
        assertTrue(outbox.expiredIngestionClaims(now + 101, 20).contains(id));
        assertEquals(1, outbox.requeueExpiredIngestion(id, now + 101));
        assertEquals("PENDING", outbox.selectById(id).status);
        assertEquals(0, documents.markReadyOwned(id, "old-consumer", 1, now + 101));
        assertEquals(1, documents.claimIngestion(id, "new-consumer", now + 101, now + 60101));
    }
}
