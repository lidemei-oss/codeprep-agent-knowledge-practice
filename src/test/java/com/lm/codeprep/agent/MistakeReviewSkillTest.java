package com.lm.codeprep.agent;

import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.knowledge.KnowledgeModels.Citation;
import com.lm.codeprep.knowledge.KnowledgeModels.RetrievalResult;
import com.lm.codeprep.knowledge.KnowledgeSearchService;
import com.lm.codeprep.knowledge.RagPromptBuilder;
import com.lm.codeprep.practice.PracticeAgentService;
import com.lm.codeprep.practice.PracticeModels.AttemptReview;
import com.lm.codeprep.practice.PracticeModels.Question;
import com.lm.codeprep.practice.PracticeModels.ReviewData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MistakeReviewSkillTest {
    @Test
    void reviewsRepeatedWeaknessAndStartsEvidenceBackedPractice() {
        PracticeAgentService practice = mock(PracticeAgentService.class);
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        DeepSeekClient llm = mock(DeepSeekClient.class);
        MistakeReviewSkill skill = new MistakeReviewSkill(practice, search, llm, new RagPromptBuilder(3000));
        var attempts = List.of(new AttemptReview("q1", "什么是缓存击穿", 35, List.of("热点键失效")),
                new AttemptReview("q2", "如何处理缓存击穿", 45, List.of("热点键失效", "互斥锁")));
        when(practice.reviewData("alice", "p1")).thenReturn(new ReviewData("p1", "java", "java", attempts, "缓存"));
        Citation evidence = new Citation("c1", "d1", "Redis", "热点键失效可通过互斥锁保护。", "java", 0, 1, "BM25");
        when(search.retrieve("热点键失效", "java", "java", "缓存"))
                .thenReturn(new RetrievalResult(List.of(evidence), 0, 1, 1, "BM25"));
        Question followUp = new Question("q3", "p2", "解释热点键失效", "OPEN_ENDED", 1, List.of(), List.of("c1"));
        when(practice.startFocused("alice", "java", "java", "缓存", "热点键失效", List.of(evidence)))
                .thenReturn(followUp);

        var result = skill.run("alice", "p1", "java", "java");

        assertEquals("COMPLETED", result.status());
        assertEquals(List.of("热点键失效", "互斥锁"), result.weakConcepts());
        assertEquals(followUp, result.question());
        assertTrue(result.explanation().contains("[1]"));
        verify(practice).startFocused("alice", "java", "java", "缓存", "热点键失效", List.of(evidence));
    }

    @Test
    void requiresAnOwnedAndMatchingPracticeSession() {
        PracticeAgentService practice = mock(PracticeAgentService.class);
        MistakeReviewSkill skill = new MistakeReviewSkill(practice, mock(KnowledgeSearchService.class),
                mock(DeepSeekClient.class), new RagPromptBuilder(3000));
        when(practice.reviewData("alice", "p1"))
                .thenReturn(new ReviewData("p1", "java", "java", List.of()));

        assertThrows(SecurityException.class, () -> skill.run("alice", "p1", "spring", "java"));
        assertEquals("NEEDS_PRACTICE_HISTORY", skill.run("alice", "p1", "java", "java").status());
    }
}
