package com.lm.codeprep.practice;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.NotBlank;

public final class PracticeModels {
    private PracticeModels() { }
    public record Session(String id, String username, String category, String orgTag, int total,
                          int completed, int score, int mastery, Instant createdAt, String groupName) {
        public Session(String id, String username, String category, String orgTag, int total,
                       int completed, int score, int mastery, Instant createdAt) {
            this(id, username, category, orgTag, total, completed, score, mastery, createdAt, null);
        }
    }
    public record Question(String id, String sessionId, String prompt, String type, int sequence,
                           List<String> hints, List<String> sourceChunkIds) { }
    public record AnswerResult(String sessionId, String questionId, int score, String feedback,
                               List<String> missingConcepts, int mastery, Question nextQuestion) { }
    public record AttemptReview(String questionId, String questionPrompt, int score,
                                List<String> missingConcepts) { }
    public record ReviewData(String sessionId, String category, String orgTag,
                             List<AttemptReview> attempts, String groupName) {
        public ReviewData(String sessionId, String category, String orgTag, List<AttemptReview> attempts) {
            this(sessionId, category, orgTag, attempts, null);
        }
    }
    public record StartRequest(@NotBlank String category, @NotBlank String orgTag, int targetCount,
                               String difficulty, String groupName) {
        public StartRequest(String category, String orgTag, int targetCount, String difficulty) {
            this(category, orgTag, targetCount, difficulty, null);
        }
    }
    public record AnswerRequest(@NotBlank String questionId, @NotBlank String requestId, @NotBlank String answer) { }
}
