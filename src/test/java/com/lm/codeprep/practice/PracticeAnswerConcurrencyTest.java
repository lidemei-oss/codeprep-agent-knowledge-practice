package com.lm.codeprep.practice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.knowledge.KnowledgeModels;
import com.lm.codeprep.knowledge.KnowledgeStore;
import com.lm.codeprep.persistence.mapper.PracticeSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.lm.codeprep.practice.PracticeModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"codeprep.elasticsearch.enabled=false", "codeprep.embedding.enabled=false",
        "codeprep.rabbitmq.enabled=false", "codeprep.redis.enabled=false"})
@ActiveProfiles("test")
class PracticeAnswerConcurrencyTest {
    @Autowired PracticeAgentService practice;
    @Autowired PracticeSessionMapper sessions;
    @Autowired KnowledgeStore knowledge;
    @Autowired ObjectMapper json;
    @MockBean DeepSeekClient llm;

    @Test
    void concurrentSameRequestNeverGradesTwiceAndModelRunsOutsideTransaction() throws Exception {
        String documentId = UUID.randomUUID().toString();
        knowledge.create(documentId, "并发", "java", "java", "threads.md", "并发");
        knowledge.saveChunk(new KnowledgeModels.Chunk(documentId + "-0", documentId, "并发",
                "线程池通过有界队列和拒绝策略控制任务积压", "java", "java", 0, null));
        knowledge.markReady(documentId, 1);

        CountDownLatch gradingStarted = new CountDownLatch(1);
        CountDownLatch finishGrading = new CountDownLatch(1);
        AtomicInteger gradingCalls = new AtomicInteger();
        when(llm.enabled()).thenReturn(true);
        when(llm.chat(anyString(), anyString())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            String system = invocation.getArgument(0);
            if (!system.contains("严格的 Java 技术评测器")) return Mono.empty();
            gradingCalls.incrementAndGet();
            return Mono.fromCallable(() -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                gradingStarted.countDown();
                if (!finishGrading.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                return "{\"score\":88,\"feedback\":\"答题正确\",\"missing\":[]}";
            });
        });

        Question first = practice.start("alice", new StartRequest("java", "java", 2, "medium", "并发"));
        AnswerRequest request = new AnswerRequest(first.id(), UUID.randomUUID().toString(), "有界队列控制积压");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstCall = executor.submit(() -> practice.answer("alice", first.sessionId(), request));
            assertTrue(gradingStarted.await(5, TimeUnit.SECONDS));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(request.requestId(), json.readTree(sessions.selectById(first.sessionId()).stateJson)
                    .path("pending").path("requestId").asText());

            assertThrows(PracticeEvaluationInProgressException.class,
                    () -> practice.answer("alice", first.sessionId(), request));
            assertThrows(PracticeEvaluationInProgressException.class, () -> practice.answer("alice", first.sessionId(),
                    new AnswerRequest(first.id(), UUID.randomUUID().toString(), "另一份答案")));
            assertThrows(IllegalStateException.class, () -> practice.answer("alice", first.sessionId(),
                    new AnswerRequest(first.id(), request.requestId(), "不同答案")));
            assertEquals(1, gradingCalls.get());

            finishGrading.countDown();
            AnswerResult result = firstCall.get(5, TimeUnit.SECONDS);
            assertEquals(88, result.score());
            assertNotNull(result.nextQuestion());
            assertEquals(result, practice.answer("alice", first.sessionId(), request));
            assertEquals(1, gradingCalls.get());
            assertEquals(1, practice.session("alice", first.sessionId()).completed());
            assertTrue(json.readTree(sessions.selectById(first.sessionId()).stateJson).path("pending").isMissingNode());
        } finally {
            finishGrading.countDown();
            executor.shutdownNow();
        }
    }
}
