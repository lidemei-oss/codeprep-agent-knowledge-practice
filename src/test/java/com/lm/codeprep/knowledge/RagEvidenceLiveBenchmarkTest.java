package com.lm.codeprep.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.infra.ElasticsearchKnowledgeGateway;
import com.lm.codeprep.infra.QwenEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.lm.codeprep.knowledge.KnowledgeModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real configured model, self-authored document only. No generation or public search. */
@EnabledIfSystemProperty(named = "codeprep.rag.evidence.benchmark", matches = "true")
class RagEvidenceLiveBenchmarkTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Path directory = Path.of("docs", "rag-benchmark");

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void evaluateRealEvidenceSelection() throws Exception {
        Properties configuration = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) { configuration.load(reader); }
        JsonNode corpus = json.readTree(directory.resolve("corpus.json").toFile());
        JsonNode original = json.readTree(directory.resolve("live-results.json").toFile());
        assertEquals(corpus.path("sha256").asText(), original.path("corpusSha256").asText());
        assertEquals("rag-benchmark", corpus.path("orgTag").asText(), "Only the self-authored test corpus is allowed");
        Map<String, JsonNode> chunks = new HashMap<>();
        for (JsonNode chunk : corpus.path("chunks")) chunks.put(chunk.path("chunkId").asText(), chunk);

        String esUrl = value(configuration, "ELASTICSEARCH_URL", "http://127.0.0.1:9200");
        String index = value(configuration, "ELASTICSEARCH_INDEX", "codeprep_knowledge_chunk");
        ElasticsearchKnowledgeGateway es = new ElasticsearchKnowledgeGateway(true, esUrl, index, 8, 40, json);
        QwenEmbeddingClient embedding = new QwenEmbeddingClient(true,
                value(configuration, "QWEN_EMBEDDING_BASE_URL", "http://127.0.0.1:11434/v1"),
                value(configuration, "QWEN_EMBEDDING_API_KEY", ""),
                value(configuration, "QWEN_EMBEDDING_MODEL", "Qwen3-Embedding-4B"), 2048, json);
        String model = value(configuration, "DEEPSEEK_MODEL", "deepseek-chat");
        DeepSeekClient llm = new DeepSeekClient(true, value(configuration, "DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
                value(configuration, "DEEPSEEK_API_KEY", ""), model, .2, json);
        EvidenceSelectionService selector = new EvidenceSelectionService(llm, json, new RagPromptBuilder(12000),
                Long.parseLong(value(configuration, "CODEPREP_EVIDENCE_TIMEOUT_MS", "30000")), 5, 20000);
        assertCorpusUnchanged(esUrl, index, corpus);

        boolean smoke = Boolean.getBoolean("codeprep.rag.evidence.smoke");
        ObjectNode report = json.createObjectNode().put("startedAt", Instant.now().toString())
                .put("requestMode", "bounded-json-v1")
                .put("model", model).put("corpusSha256", corpus.path("sha256").asText())
                .put("documentId", corpus.path("documentId").asText()).put("smoke", smoke)
                .put("generationEnabled", false).put("webSearchEnabled", false);
        ArrayNode results = report.putArray("cases");
        Path resultFile = directory.resolve(smoke ? "evidence-smoke-results.json" : "evidence-live-results.json");
        Set<String> completedIds = new java.util.HashSet<>();
        if (!smoke && Boolean.getBoolean("codeprep.rag.evidence.resume") && Files.exists(resultFile)) {
            ObjectNode previous = (ObjectNode) json.readTree(resultFile.toFile());
            assertEquals(report.path("corpusSha256").asText(), previous.path("corpusSha256").asText());
            assertEquals(model, previous.path("model").asText());
            assertEquals("bounded-json-v1", previous.path("requestMode").asText(), "Do not mix different model request modes");
            assertFalse(previous.has("completedAt"), "Only an incomplete measurement can be resumed");
            report = previous;
            report.put("resumedAt", Instant.now().toString());
            results = (ArrayNode) report.path("cases");
            for (JsonNode prior : results) completedIds.add(prior.path("label").path("id").asText());
        }
        for (JsonNode row : original.path("cases")) {
            String id = row.path("label").path("id").asText();
            if (smoke && !Set.of("Q22", "N04", "M03").contains(id)) continue;
            if (completedIds.contains(id)) continue;
            List<Citation> candidates = json.convertValue(row.path("currentHybrid"), new TypeReference<>() { });
            evaluate(selector, row.path("label"), candidates, "regression", chunks, results);
            json.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), report);
            stopIfUnavailable(report, resultFile);
        }
        if (!smoke) {
            JsonNode holdout = json.readTree(directory.resolve("evidence-holdout.json").toFile());
            for (JsonNode item : holdout.path("cases")) {
                if (completedIds.contains(item.path("id").asText())) continue;
                ObjectNode label = item.deepCopy();
                ArrayNode numbers = label.putArray("relevantChunkNos");
                if (!"absent".equals(label.path("type").asText())) {
                    List<String> anchors = new ArrayList<>();
                    if (label.has("evidenceAnchors")) label.path("evidenceAnchors").forEach(a -> anchors.add(a.asText()));
                    else anchors.add(label.path("evidence").asText());
                    for (JsonNode chunk : corpus.path("chunks"))
                        if (anchors.stream().anyMatch(a -> chunk.path("content").asText().contains(a)))
                            numbers.add(chunk.path("chunkNo").asInt());
                    assertFalse(numbers.isEmpty(), "Holdout evidence must exist before evaluating it");
                }
                var raw = es.hybridSearch(label.path("question").asText(), corpus.path("orgTag").asText(),
                        corpus.path("category").asText(), embedding.embed(label.path("question").asText()));
                evaluate(selector, label, raw.citations(), "holdout", chunks, results);
                json.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), report);
                stopIfUnavailable(report, resultFile);
            }
        }
        assertCorpusUnchanged(esUrl, index, corpus);
        report.put("completedAt", Instant.now().toString()).put("corpusUnchanged", true);
        json.writerWithDefaultPrettyPrinter().writeValue(resultFile.toFile(), report);
    }

    private void evaluate(EvidenceSelectionService selector, JsonNode label, List<Citation> candidates,
                          String split, Map<String, JsonNode> chunks, ArrayNode results) {
        for (Citation candidate : candidates) assertTrue(chunks.containsKey(candidate.chunkId()), "Foreign document candidate");
        long start = System.nanoTime();
        EvidenceSelection selection = selector.select(label.path("question").asText(), candidates);
        ObjectNode row = results.addObject();
        row.set("label", label); row.put("split", split); row.set("candidates", json.valueToTree(candidates));
        row.set("selection", json.valueToTree(selection));
        row.put("verificationMs", (System.nanoTime() - start) / 1_000_000.0);
        System.out.printf("EVIDENCE_LIVE %s %s selected=%d reason=%s%n", label.path("id").asText(),
                selection.status(), selection.citations().size(), selection.reason());
        // Availability is measured, not converted into a successful rejection or removed from the dataset.
        if (selection.status() == EvidenceStatus.ERROR) assertTrue(selection.citations().isEmpty());
    }

    private void assertCorpusUnchanged(String url, String index, JsonNode corpus) throws Exception {
        ObjectNode query = json.createObjectNode().put("size", 10000);
        query.putObject("query").putObject("term").put("documentId", corpus.path("documentId").asText());
        query.putObject("_source").putArray("excludes").add("embedding");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/" + index + "/_search"))
                .timeout(java.time.Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(query))).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode hits = json.readTree(response.body()).path("hits").path("hits");
        assertEquals(corpus.path("chunks").size(), hits.size());
        Map<String, JsonNode> originals = new HashMap<>();
        for (JsonNode chunk : corpus.path("chunks")) originals.put(chunk.path("chunkId").asText(), chunk);
        for (JsonNode hit : hits) {
            JsonNode current = hit.path("_source");
            JsonNode original = originals.get(current.path("chunkId").asText());
            assertNotNull(original);
            assertEquals("READY", current.path("status").asText());
            assertEquals(original.path("content").asText(), current.path("content").asText());
        }
    }

    private String value(Properties properties, String key, String fallback) {
        return System.getenv().getOrDefault(key, properties.getProperty(key, fallback));
    }

    private void stopIfUnavailable(ObjectNode report, Path file) throws Exception {
        JsonNode results = report.path("cases");
        if (results.size() < 3) return;
        for (int i = results.size() - 3; i < results.size(); i++)
            if (!"ERROR".equals(results.get(i).path("selection").path("status").asText())) return;
        report.put("abortedReason", "Three consecutive verification service failures; no quality percentage reported");
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        fail("Model verification service is unavailable; all measured errors have been preserved");
    }
}
