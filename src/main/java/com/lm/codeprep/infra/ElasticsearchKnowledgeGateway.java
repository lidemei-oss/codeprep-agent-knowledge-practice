package com.lm.codeprep.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lm.codeprep.knowledge.KnowledgeModels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lm.codeprep.knowledge.KnowledgeModels.Citation;
import static com.lm.codeprep.knowledge.KnowledgeModels.Chunk;

/** ES 8 的 IK 分词、KNN 向量召回和 BM25 召回编排。最终用 RRF 做轻量重排序。 */
@Service
public class ElasticsearchKnowledgeGateway {
    private final boolean enabled;
    private final String url;
    private final String index;
    private final int topK;
    private final int candidateK;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public ElasticsearchKnowledgeGateway(@Value("${codeprep.elasticsearch.enabled:false}") boolean enabled,
                                        @Value("${codeprep.elasticsearch.url}") String url,
                                        @Value("${codeprep.elasticsearch.index}") String index,
                                        @Value("${codeprep.elasticsearch.top-k:8}") int topK,
                                        @Value("${codeprep.elasticsearch.candidate-k:40}") int candidateK,
                                        ObjectMapper mapper) {
        this.enabled = enabled; this.url = url; this.index = index; this.topK = topK; this.candidateK = candidateK; this.mapper = mapper;
    }

    public boolean enabled() { return enabled; }

    public void ensureIndex() {
        if (!enabled) return;
        ObjectNode root = mapper.createObjectNode();
        ObjectNode settings = root.putObject("settings");
        settings.putObject("analysis").putObject("analyzer").putObject("ik_text")
                .put("type", "custom").put("tokenizer", "ik_max_word");
        ObjectNode properties = root.putObject("mappings").putObject("properties");
        properties.putObject("documentId").put("type", "keyword");
        properties.putObject("chunkId").put("type", "keyword");
        properties.putObject("title").put("type", "text").put("analyzer", "ik_text");
        properties.putObject("content").put("type", "text").put("analyzer", "ik_text");
        properties.putObject("keywords").put("type", "text").put("analyzer", "ik_max_word");
        properties.putObject("category").put("type", "keyword");
        properties.putObject("orgTag").put("type", "keyword");
        properties.putObject("status").put("type", "keyword");
        properties.putObject("embedding").put("type", "dense_vector").put("dims", 2048).put("index", true).put("similarity", "cosine");
        try { send("PUT", "/" + index, root); }
        catch (Exception exception) {
            if (!String.valueOf(exception.getMessage()).contains("resource_already_exists_exception"))
                throw new IllegalStateException("ES 索引初始化失败: " + exception.getMessage(), exception);
        }
    }

    public void index(Chunk chunk) {
        if (!enabled) return;
        ObjectNode body = mapper.createObjectNode().put("documentId", chunk.documentId()).put("chunkId", chunk.id())
                .put("title", chunk.title()).put("content", chunk.content()).put("category", chunk.category())
                .put("orgTag", chunk.orgTag()).put("chunkNo", chunk.chunkNo()).put("status", "PROCESSING");
        if (chunk.embedding() != null) { ArrayNode vector = body.putArray("embedding"); chunk.embedding().forEach(vector::add); }
        try { send("PUT", "/" + index + "/_doc/" + chunk.id() + "?refresh=wait_for", body); } catch (Exception exception) { throw new IllegalStateException("ES 索引写入失败: " + exception.getMessage(), exception); }
    }

    public void markDocumentReady(String documentId) {
        if (!enabled) return;
        ObjectNode body = documentQuery(documentId);
        body.putObject("script").put("source", "ctx._source.status = 'READY'").put("lang", "painless");
        try { send("POST", "/" + index + "/_update_by_query?refresh=true&conflicts=proceed", body); }
        catch (Exception exception) { throw new IllegalStateException("ES 文档发布失败: " + exception.getMessage(), exception); }
    }

    public void deleteDocument(String documentId) {
        if (!enabled) return;
        try { send("POST", "/" + index + "/_delete_by_query?refresh=true&conflicts=proceed", documentQuery(documentId)); }
        catch (Exception exception) { throw new IllegalStateException("ES 文档清理失败: " + exception.getMessage(), exception); }
    }

    private ObjectNode documentQuery(String documentId) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode should = body.putObject("query").putObject("bool");
        ArrayNode alternatives = should.putArray("should");
        alternatives.addObject().putObject("term").put("documentId", documentId);
        alternatives.addObject().putObject("term").put("documentId.keyword", documentId);
        should.put("minimum_should_match", 1);
        return body;
    }

    public KnowledgeModels.RetrievalResult hybridSearch(String question, String orgTag, String category, List<Double> vector) {
        return hybridSearch(question, orgTag, category, vector, null);
    }

    public KnowledgeModels.RetrievalResult hybridSearch(String question, String orgTag, String category,
                                                        List<Double> vector, Set<String> documentIds) {
        if (!enabled) return new KnowledgeModels.RetrievalResult(List.of(), 0, 0, 0, "DISABLED");
        if (documentIds != null && documentIds.isEmpty())
            return new KnowledgeModels.RetrievalResult(List.of(), 0, 0, 0, "EMPTY_GROUP");
        try {
            List<Citation> keyword = keyword(question, orgTag, category, documentIds);
            List<Citation> semantic = vector == null ? List.of() : knn(vector, orgTag, category, documentIds);
            Map<String, CitationScore> merged = new HashMap<>();
            for (int i = 0; i < keyword.size(); i++) merge(merged, keyword.get(i), i + 1, false);
            for (int i = 0; i < semantic.size(); i++) merge(merged, semantic.get(i), i + 1, true);
            List<Citation> reranked = merged.values().stream().sorted((a, b) -> Double.compare(b.score, a.score))
                    .limit(topK).map(a -> new Citation(a.citation.chunkId(), a.citation.documentId(), a.citation.title(), a.citation.content(),
                            a.citation.category(), a.citation.chunkNo(), a.score, "ES_HYBRID_RRF")).toList();
            return new KnowledgeModels.RetrievalResult(reranked, semantic.size(), keyword.size(), reranked.size(), "KNN+BM25+RRF");
        } catch (Exception exception) {
            throw new IllegalStateException("ES 混合检索失败: " + exception.getMessage(), exception);
        }
    }

    public List<Citation> sampleReady(String orgTag, String category, Set<String> documentIds, int limit) {
        if (!enabled || documentIds != null && documentIds.isEmpty()) return List.of();
        try {
            ObjectNode root = mapper.createObjectNode().put("size", limit);
            ObjectNode bool = root.putObject("query").putObject("bool");
            filters(bool, orgTag, category, documentIds);
            return hits(send("POST", "/" + index + "/_search", root), "ES_SAMPLE");
        } catch (Exception exception) {
            throw new IllegalStateException("ES 题源采样失败: " + exception.getMessage(), exception);
        }
    }

    private void merge(Map<String, CitationScore> target, Citation citation, int rank, boolean vector) {
        CitationScore score = target.computeIfAbsent(citation.chunkId(), ignored -> new CitationScore(citation));
        score.score += 1.0 / (60 + rank);
        if (vector) score.score += Math.max(0, citation.score()) * 0.05;
        else score.score += Math.min(1.0, Math.max(0, citation.score()) / 10.0) * 0.2;
    }

    private List<Citation> keyword(String question, String orgTag, String category, Set<String> documentIds) throws Exception {
        ObjectNode root = mapper.createObjectNode().put("size", candidateK);
        ObjectNode bool = root.putObject("query").putObject("bool");
        bool.putObject("must").putObject("multi_match").put("query", question).putArray("fields").add("title^3").add("content").add("keywords^2");
        filters(bool, orgTag, category, documentIds);
        return hits(send("POST", "/" + index + "/_search", root), "BM25");
    }

    private List<Citation> knn(List<Double> vector, String orgTag, String category, Set<String> documentIds) throws Exception {
        ObjectNode root = mapper.createObjectNode().put("size", candidateK);
        ObjectNode knn = root.putObject("knn").put("field", "embedding").put("k", topK).put("num_candidates", candidateK);
        ArrayNode queryVector = knn.putArray("query_vector"); vector.forEach(queryVector::add);
        ObjectNode filter = knn.putObject("filter").putObject("bool"); filters(filter, orgTag, category, documentIds);
        return hits(send("POST", "/" + index + "/_search", root), "KNN");
    }

    private void filters(ObjectNode bool, String orgTag, String category, Set<String> documentIds) {
        ArrayNode filters = bool.putArray("filter");
        filters.addObject().putObject("term").put("status", "READY");
        if (orgTag != null && !orgTag.isBlank()) filters.addObject().putObject("term").put("orgTag", orgTag);
        if (category != null && !category.isBlank()) filters.addObject().putObject("term").put("category", category);
        if (documentIds != null) {
            ArrayNode ids = filters.addObject().putObject("terms").putArray("documentId");
            documentIds.forEach(ids::add);
        }
    }

    private List<Citation> hits(String json, String source) throws Exception {
        List<Citation> result = new ArrayList<>();
        for (JsonNode hit : mapper.readTree(json).path("hits").path("hits")) {
            JsonNode fields = hit.path("_source");
            result.add(new Citation(fields.path("chunkId").asText(hit.path("_id").asText()), fields.path("documentId").asText(),
                    fields.path("title").asText(), fields.path("content").asText(), fields.path("category").asText(),
                    fields.path("chunkNo").asInt(), hit.path("_score").asDouble(0), source));
        }
        return result;
    }

    private String send(String method, String path, ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new IllegalStateException(response.body());
        return response.body();
    }

    private static final class CitationScore {
        private final Citation citation; private double score;
        private CitationScore(Citation citation) { this.citation = citation; }
    }
}
