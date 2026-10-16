package com.lm.codeprep.practice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.knowledge.KnowledgeModels.Citation;
import com.lm.codeprep.knowledge.KnowledgeModels.RetrievalResult;
import com.lm.codeprep.knowledge.KnowledgeSearchService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.lm.codeprep.practice.PracticeModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PracticeAgentServiceTest {
    @Test
    void onlyKnowledgeBackedQuestionsAndIdempotentAnswerReplays() {
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        DeepSeekClient llm = mock(DeepSeekClient.class);
        when(search.retrieve(anyString(), eq("java"), eq("java")))
                .thenReturn(new RetrievalResult(List.of(), 0, 0, 0, "EMPTY"));
        when(search.samples("java", "java", 3)).thenReturn(List.of(
                new Citation("c1", "d1", "HashMap", "文档：noise-title-xyz.md\n章节：HashMap\nHashMap 使用数组和链表处理哈希冲突", "java", 0, 1, "SAMPLE")));
        when(llm.chat(anyString(), anyString())).thenReturn(Mono.empty());
        PracticeAgentService service = new PracticeAgentService(search, llm, new ObjectMapper());

        Question question = service.start("alice", new StartRequest("java", "java", 1, "medium"));
        AnswerRequest answer = new AnswerRequest(question.id(), "retry-1", "HashMap 通过数组与链表处理哈希冲突，原理是定位桶并比较 key。");
        AnswerResult first = service.answer("alice", question.sessionId(), answer);
        AnswerResult retry = service.answer("alice", question.sessionId(), answer);

        assertSame(first, retry);
        assertEquals(1, service.session("alice", question.sessionId()).completed());
        assertEquals(1, service.reviewData("alice", question.sessionId()).attempts().size());
        assertFalse(first.missingConcepts().contains("noise"));
        assertFalse(first.missingConcepts().contains("title"));
        assertThrows(SecurityException.class, () -> service.session("reader", question.sessionId()));
        assertThrows(SecurityException.class, () -> service.reviewData("reader", question.sessionId()));
        assertThrows(IllegalStateException.class, () -> service.answer("alice", question.sessionId(),
                new AnswerRequest(question.id(), "retry-1", "不同答案")));
    }

    @Test
    void rejectsLongMarkdownQuestionAndUsesSourceChapter() {
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        DeepSeekClient llm = mock(DeepSeekClient.class);
        when(search.retrieve(anyString(), eq("java"), eq("java")))
                .thenReturn(new RetrievalResult(List.of(), 0, 0, 0, "EMPTY"));
        when(search.samples("java", "java", 3)).thenReturn(List.of(new Citation("c1", "d1", "面试手册.md",
                "文档：面试手册.md\n章节：并发 > 3 AQS 同步器如何工作\nAQS 使用状态和等待队列。",
                "java", 0, 1, "SAMPLE")));
        when(llm.chat(anyString(), anyString()))
                .thenReturn(Mono.just("## 开放题\n请设计复杂的并发框架。".repeat(20)));
        PracticeAgentService service = new PracticeAgentService(search, llm, new ObjectMapper());

        Question question = service.start("alice", new StartRequest("java", "java", 1, "medium"));

        assertTrue(question.prompt().contains("AQS 同步器如何工作"));
        assertTrue(question.prompt().length() < 180);
        assertFalse(question.prompt().contains("#"));
    }

    @Test
    void failedNextQuestionGenerationReleasesReservationForRetry() {
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        DeepSeekClient llm = mock(DeepSeekClient.class);
        Citation citation = new Citation("c1", "d1", "线程池", "线程池使用有界队列控制积压",
                "java", 0, 1, "SAMPLE");
        when(search.retrieve(anyString(), eq("java"), eq("java")))
                .thenReturn(new RetrievalResult(List.of(), 0, 0, 0, "EMPTY"));
        when(search.samples("java", "java", 3))
                .thenReturn(List.of(citation), List.of(), List.of(citation));
        when(llm.chat(anyString(), anyString())).thenReturn(Mono.empty());
        PracticeAgentService service = new PracticeAgentService(search, llm, new ObjectMapper());

        Question question = service.start("alice", new StartRequest("java", "java", 2, "medium"));
        AnswerRequest request = new AnswerRequest(question.id(), "retry-after-error", "有界队列控制积压");
        assertThrows(IllegalStateException.class, () -> service.answer("alice", question.sessionId(), request));
        assertEquals(0, service.session("alice", question.sessionId()).completed());

        AnswerResult retried = service.answer("alice", question.sessionId(), request);
        assertNotNull(retried.nextQuestion());
        assertEquals(1, service.session("alice", question.sessionId()).completed());
    }
}
