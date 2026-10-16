package com.lm.codeprep.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.infra.DeepSeekClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AgentPlannerSkillTest {
    private final AgentPlanner planner = new AgentPlanner(mock(DeepSeekClient.class), new ObjectMapper());

    @Test
    void practiceSessionWithoutAnswerRunsMistakeReview() {
        assertEquals(AgentPlanner.Action.REVIEW_MISTAKES,
                planner.decide(new AgentModels.RunRequest("复盘错题", null, "java", "java",
                        "practice-1", null, null, null)));
    }

    @Test
    void answerSubmissionStillRoutesToGrading() {
        assertEquals(AgentPlanner.Action.GRADE_ANSWER,
                planner.decide(new AgentModels.RunRequest("评估答案", null, "java", "java",
                        "practice-1", "question-1", "retry-1", "我的回答")));
    }
}
