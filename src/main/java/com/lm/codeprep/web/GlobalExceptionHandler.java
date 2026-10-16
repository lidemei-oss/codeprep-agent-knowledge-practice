package com.lm.codeprep.web;

import com.lm.codeprep.practice.PracticeEvaluationInProgressException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(PracticeEvaluationInProgressException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> evaluationInProgress(PracticeEvaluationInProgressException exception) {
        return Map.of("timestamp", Instant.now(), "error", exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(RuntimeException exception) { return Map.of("timestamp", Instant.now(), "error", exception.getMessage()); }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, Object> forbidden(SecurityException exception) { return Map.of("timestamp", Instant.now(), "error", exception.getMessage()); }
}
