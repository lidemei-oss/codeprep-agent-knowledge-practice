package com.lm.codeprep.knowledge;

import com.lm.codeprep.agent.AgentPlanner;
import com.lm.codeprep.infra.WebSearchClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.lm.codeprep.knowledge.KnowledgeModels.*;

/** Shared retrieval/rewrite/web/evidence policy for HTTP, SSE, WebSocket and Agent answers. */
@Service
public class GroundedKnowledgeService {
    private final KnowledgeSearchService search;
    private final AgentPlanner planner;
    private final WebSearchClient web;
    private final EvidenceSelectionService selection;

    public GroundedKnowledgeService(KnowledgeSearchService search, AgentPlanner planner,
                                    WebSearchClient web, EvidenceSelectionService selection) {
        this.search = search; this.planner = planner; this.web = web; this.selection = selection;
    }

    public RetrievalResult retrieve(String question, String orgTag, String category, boolean allowWeb) {
        return retrieve(question, orgTag, category, allowWeb, List.of());
    }

    public RetrievalResult retrieve(String question, String orgTag, String category, boolean allowWeb, List<String> history) {
        List<EvidenceAttempt> attempts = new ArrayList<>();
        RetrievalResult result = check(question, question, privateSearch(question, orgTag, category), attempts, history);
        if (terminal(result)) return result;
        String rewritten = planner.rewrite(question);
        if (rewritten != null && !rewritten.isBlank() && !rewritten.equals(question)) {
            // Judge rewritten-query candidates against the ORIGINAL user intent.
            result = check(question, rewritten, privateSearch(rewritten, orgTag, category), attempts, history);
            if (terminal(result)) return result;
        }
        if (!allowWeb) return finish(result, "WEB_SEARCH_SKIPPED", EvidenceStatus.INSUFFICIENT,
                "NO_SUFFICIENT_EVIDENCE", attempts);
        try {
            List<Citation> candidates = web.search(question, category);
            result = check(question, question, new RetrievalResult(candidates, 0, 0, candidates.size(), "WEB_SEARCH"), attempts, history);
            if (terminal(result)) return result;
            return finish(result, "NEEDS_SOURCE", EvidenceStatus.INSUFFICIENT, "NO_SUFFICIENT_EVIDENCE", attempts);
        } catch (RuntimeException exception) {
            return finish(result, "WEB_SEARCH_UNAVAILABLE", EvidenceStatus.ERROR, "WEB_SEARCH_FAILED", attempts);
        }
    }

    private RetrievalResult privateSearch(String query, String orgTag, String category) {
        try { return search.retrieve(query, orgTag, category); }
        catch (RuntimeException exception) {
            return new RetrievalResult(List.of(), 0, 0, 0, "PRIVATE_INDEX_UNAVAILABLE");
        }
    }

    private RetrievalResult check(String original, String query, RetrievalResult raw, List<EvidenceAttempt> attempts, List<String> history) {
        EvidenceSelection evidence = "PRIVATE_INDEX_UNAVAILABLE".equals(raw.strategy())
                ? new EvidenceSelection(EvidenceStatus.ERROR, List.of(), "PRIVATE_RETRIEVAL_FAILED")
                : raw.citations().isEmpty()
                    ? new EvidenceSelection(EvidenceStatus.INSUFFICIENT, List.of(), "NO_CANDIDATES")
                    : history.isEmpty() ? selection.select(original, raw.citations())
                        : selection.select(original, raw.citations(), history);
        boolean unavailableWithoutSupport = raw.privateIndexUnavailable() && evidence.status() == EvidenceStatus.INSUFFICIENT;
        if (unavailableWithoutSupport)
            evidence = new EvidenceSelection(EvidenceStatus.ERROR, List.of(), "PRIVATE_RETRIEVAL_DEGRADED_WITHOUT_SUPPORT");
        attempts.add(new EvidenceAttempt(query, raw.strategy(), raw.vectorHits(), raw.keywordHits(), raw.citations(), evidence,
                raw.privateIndexUnavailable()));
        String strategy = "PRIVATE_INDEX_UNAVAILABLE".equals(raw.strategy()) || unavailableWithoutSupport ? "PRIVATE_INDEX_UNAVAILABLE"
                : evidence.status() == EvidenceStatus.ERROR ? "EVIDENCE_VERIFICATION_UNAVAILABLE" : raw.strategy();
        return new RetrievalResult(evidence.citations(), raw.vectorHits(), raw.keywordHits(), raw.rerankedHits(),
                strategy, new EvidenceReport(evidence.status(), evidence.reason(), attempts), raw.privateIndexUnavailable());
    }

    private boolean terminal(RetrievalResult result) {
        return result.evidence().status() != EvidenceStatus.INSUFFICIENT;
    }

    private RetrievalResult finish(RetrievalResult previous, String strategy, EvidenceStatus status,
                                    String reason, List<EvidenceAttempt> attempts) {
        return new RetrievalResult(List.of(), previous.vectorHits(), previous.keywordHits(), previous.rerankedHits(),
                strategy, new EvidenceReport(status, reason, attempts), previous.privateIndexUnavailable());
    }
}
