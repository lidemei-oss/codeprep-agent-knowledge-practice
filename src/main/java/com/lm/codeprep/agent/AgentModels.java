package com.lm.codeprep.agent;

import com.lm.codeprep.knowledge.KnowledgeModels.Citation;
import com.lm.codeprep.knowledge.KnowledgeModels.EvidenceReport;
import com.lm.codeprep.practice.PracticeModels.AnswerResult;
import com.lm.codeprep.practice.PracticeModels.Question;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;

public final class AgentModels {
    private AgentModels() { }
    public record RunRequest(@NotBlank String task, String sessionId, @NotBlank String category,
                             @NotBlank String orgTag, String practiceSessionId, String questionId,
                             String requestId, String answer, Boolean allowWebSearch, String groupName) {
        public RunRequest(String task, String sessionId, String category, String orgTag,
                          String practiceSessionId, String questionId, String requestId, String answer,
                          Boolean allowWebSearch) {
            this(task, sessionId, category, orgTag, practiceSessionId, questionId, requestId, answer,
                    allowWebSearch, null);
        }
        public RunRequest(String task, String sessionId, String category, String orgTag,
                          String practiceSessionId, String questionId, String requestId, String answer) {
            this(task, sessionId, category, orgTag, practiceSessionId, questionId, requestId, answer, true, null);
        }
    }
    public record Step(int number, String action, String observation, Instant at) { }
    public record SkillReport(String skillId, String sourcePracticeSessionId,
                              List<String> weakConcepts, String reviewPracticeSessionId) { }
    public record RunResult(String runId, String sessionId, String intent, String status,
                            String answer, Question question, AnswerResult evaluation,
                            List<Citation> citations, List<Step> steps, Instant createdAt, SkillReport skill,
                            EvidenceReport evidence) {
        public RunResult(String runId, String sessionId, String intent, String status,
                         String answer, Question question, AnswerResult evaluation,
                         List<Citation> citations, List<Step> steps, Instant createdAt, SkillReport skill) {
            this(runId, sessionId, intent, status, answer, question, evaluation, citations, steps, createdAt, skill, null);
        }
        public RunResult(String runId, String sessionId, String intent, String status,
                         String answer, Question question, AnswerResult evaluation,
                         List<Citation> citations, List<Step> steps, Instant createdAt) {
            this(runId, sessionId, intent, status, answer, question, evaluation, citations, steps, createdAt, null, null);
        }
    }
}
