package com.lm.codeprep.knowledge;

import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.NotBlank;

public final class KnowledgeModels {
    private KnowledgeModels() { }
    public static final String DEFAULT_GROUP = "默认分组";

    public static String normalizeGroup(String value) {
        if (value == null || value.isBlank()) return DEFAULT_GROUP;
        String group = value.strip().replaceAll("\\s+", " ");
        if (group.length() > 64 || group.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("分组名称不能超过 64 个字符，也不能包含控制字符");
        return group;
    }

    public record StoredDocument(String id, String title, String category, String orgTag,
                                 String status, String fileName, Instant createdAt, int chunkCount, String groupName) {
        public StoredDocument { groupName = normalizeGroup(groupName); }
        public StoredDocument(String id, String title, String category, String orgTag,
                              String status, String fileName, Instant createdAt, int chunkCount) {
            this(id, title, category, orgTag, status, fileName, createdAt, chunkCount, DEFAULT_GROUP);
        }
    }

    public record Chunk(String id, String documentId, String title, String content, String category,
                        String orgTag, int chunkNo, List<Double> embedding) { }

    public record Citation(String chunkId, String documentId, String title, String content,
                           String category, int chunkNo, double score, String retrievalSource, String url) {
        public Citation(String chunkId, String documentId, String title, String content,
                        String category, int chunkNo, double score, String retrievalSource) {
            this(chunkId, documentId, title, content, category, chunkNo, score, retrievalSource, null);
        }
    }

    public enum EvidenceStatus { SUPPORTED, INSUFFICIENT, ERROR }
    public record EvidenceSelection(EvidenceStatus status, List<Citation> citations, String reason) {
        public EvidenceSelection { citations = List.copyOf(citations); }
    }
    public record EvidenceAttempt(String query, String strategy, int vectorHits, int keywordHits,
                                  List<Citation> candidates, EvidenceSelection selection, boolean privateIndexUnavailable) {
        public EvidenceAttempt { candidates = List.copyOf(candidates); }
        public EvidenceAttempt(String query, String strategy, int vectorHits, int keywordHits,
                               List<Citation> candidates, EvidenceSelection selection) {
            this(query, strategy, vectorHits, keywordHits, candidates, selection,
                    "PRIVATE_INDEX_UNAVAILABLE".equals(strategy));
        }
    }
    public record EvidenceReport(EvidenceStatus status, String reason, List<EvidenceAttempt> attempts) {
        public EvidenceReport { attempts = List.copyOf(attempts); }
    }
    public record RetrievalResult(List<Citation> citations, int vectorHits, int keywordHits,
                                  int rerankedHits, String strategy, EvidenceReport evidence, boolean privateIndexUnavailable) {
        public RetrievalResult(List<Citation> citations, int vectorHits, int keywordHits,
                               int rerankedHits, String strategy, EvidenceReport evidence) {
            this(citations, vectorHits, keywordHits, rerankedHits, strategy, evidence,
                    "PRIVATE_INDEX_UNAVAILABLE".equals(strategy));
        }
        public RetrievalResult(List<Citation> citations, int vectorHits, int keywordHits,
                               int rerankedHits, String strategy) {
            this(citations, vectorHits, keywordHits, rerankedHits, strategy, null,
                    "PRIVATE_INDEX_UNAVAILABLE".equals(strategy));
        }
    }

    public record RagResponse(String sessionId, String answer, List<Citation> citations,
                              String promptPreview, RetrievalResult retrieval) { }

    public record AgentEvent(String type, String content, List<Citation> citations, EvidenceReport evidence) {
        public AgentEvent(String type, String content, List<Citation> citations) {
            this(type, content, citations, null);
        }
    }

    public record ChatRequest(String sessionId, @NotBlank String question, @NotBlank String category,
                              @NotBlank String orgTag, Boolean allowWebSearch) {
        public ChatRequest(String sessionId, String question, String category, String orgTag) {
            this(sessionId, question, category, orgTag, true);
        }
    }

    public record DocumentView(String id, String title, String category, String orgTag,
                               String status, String fileName, Instant createdAt, int chunkCount, String groupName) { }
    public record GroupView(String category, String orgTag, String groupName,
                            long documentCount, long readyCount) { }
}
