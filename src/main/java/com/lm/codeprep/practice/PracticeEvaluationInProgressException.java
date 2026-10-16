package com.lm.codeprep.practice;

/** A persisted reservation prevents concurrent grading of the same practice session. */
public class PracticeEvaluationInProgressException extends RuntimeException {
    public PracticeEvaluationInProgressException(String message) { super(message); }
}
