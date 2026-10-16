package com.lm.codeprep.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lm.codeprep.infra.ElasticsearchKnowledgeGateway;
import com.lm.codeprep.infra.QwenEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.lm.codeprep.knowledge.KnowledgeModels.Citation;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, read-only live benchmark. Uses the production Java ES gateway and Qwen client, never mocks. */
@EnabledIfSystemProperty(named = "codeprep.rag.benchmark", matches = "true")
class RagRetrievalLiveBenchmarkTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final Path output = Path.of("docs", "rag-benchmark");

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    @SuppressWarnings("unchecked")
    void benchmarkExistingReadyCorpus() throws Exception {
        Properties configuration = new Properties();
        if (Files.exists(Path.of(".env"))) {
            try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) {
                configuration.load(reader);
            }
        }
        String esUrl = value(configuration, "ELASTICSEARCH_URL", "http://127.0.0.1:9200");
        String index = value(configuration, "ELASTICSEARCH_INDEX", "codeprep_knowledge_chunk");
        String embeddingUrl = value(configuration, "QWEN_EMBEDDING_BASE_URL", "http://127.0.0.1:11434/v1");
        String model = value(configuration, "QWEN_EMBEDDING_MODEL", "Qwen3-Embedding-4B");
        requireLocal(esUrl);
        requireLocal(embeddingUrl);
        JsonNode cases = json.readTree(output.resolve("cases.json").toFile());
        JsonNode corpus = json.readTree(output.resolve("corpus.json").toFile());
        assertEquals(corpus.path("sha256").asText(), cases.path("corpusSha256").asText());
        assertEquals(index, corpus.path("index").asText());
        String orgTag = cases.path("orgTag").asText();
        String category = cases.path("category").asText();
        String documentId = cases.path("documentId").asText();
        Map<String, JsonNode> frozen = new HashMap<>();
        for (JsonNode chunk : corpus.path("chunks")) frozen.put(chunk.path("chunkId").asText(), chunk);
        for (JsonNode item : cases.path("cases")) {
            for (JsonNode number : item.path("relevantChunkNos")) {
                JsonNode evidence = frozen.get(documentId + "-" + number.asInt());
                assertNotNull(evidence, item.path("id").asText() + " has an unknown relevance label");
                boolean supported = item.has("evidenceAnchors")
                        ? java.util.stream.StreamSupport.stream(item.path("evidenceAnchors").spliterator(), false)
                            .anyMatch(a -> evidence.path("content").asText().contains(a.asText()))
                        : evidence.path("content").asText().contains(item.path("evidence").asText());
                assertTrue(supported,
                        item.path("id").asText() + " evidence text does not match the source");
            }
        }
        checkSnapshot(esUrl, index, orgTag, category, frozen);
        ElasticsearchKnowledgeGateway gateway = new ElasticsearchKnowledgeGateway(true, esUrl, index, 8, 40, json);
        QwenEmbeddingClient embedding = new QwenEmbeddingClient(true, embeddingUrl,
                value(configuration, "QWEN_EMBEDDING_API_KEY", ""), model, 2048, json);
        Method keyword = ElasticsearchKnowledgeGateway.class.getDeclaredMethod("keyword", String.class,
                String.class, String.class, Set.class);
        Method knn = ElasticsearchKnowledgeGateway.class.getDeclaredMethod("knn", List.class,
                String.class, String.class, Set.class);
        keyword.setAccessible(true);
        knn.setAccessible(true);

        ObjectNode report = json.createObjectNode();
        report.put("startedAt", Instant.now().toString());
        report.put("corpusSha256", corpus.path("sha256").asText());
        report.put("index", index);
        report.put("model", model);
        report.put("dimension", 2048);
        report.put("topK", 8);
        report.put("candidateK", 40);
        report.put("corpusChunks", frozen.size());
        report.put("usesProductionJavaGateway", true);
        report.put("queryRewriteEnabled", false);
        report.put("webSearchEnabled", false);
        report.put("generationEnabled", false);
        ArrayNode rows = report.putArray("cases");

        // Warmup is excluded from query latency statistics, and checks index/model consistency.
        JsonNode anchor = corpus.path("chunks").get(0);
        long warmupStart = System.nanoTime();
        List<Double> anchorVector = embedding.embed(anchor.path("content").asText());
        assertEquals(2048, anchorVector.size());
        report.put("warmupEmbeddingMs", elapsed(warmupStart));
        ObjectNode anchorQuery = json.createObjectNode().put("size", 1);
        anchorQuery.putObject("query").putObject("term").put("chunkId", anchor.path("chunkId").asText());
        anchorQuery.putArray("_source").add("embedding");
        JsonNode stored = es(esUrl, index, anchorQuery).path("hits").path("hits").get(0).path("_source").path("embedding");
        assertEquals(2048, stored.size());
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < 2048; i++) {
            double a = anchorVector.get(i), b = stored.get(i).asDouble();
            dot += a * b; normA += a * a; normB += b * b;
        }
        report.put("reembeddedAnchorCosine", dot / Math.sqrt(normA * normB));

        for (JsonNode item : cases.path("cases")) {
            String question = item.path("question").asText();
            long start = System.nanoTime();
            List<Double> vector = embedding.embed(question);
            double embeddingMs = elapsed(start);
            assertEquals(2048, vector.size());
            start = System.nanoTime();
            List<Citation> lexical = (List<Citation>) keyword.invoke(gateway, question, orgTag, category, null);
            double bm25Ms = elapsed(start);
            start = System.nanoTime();
            List<Citation> semantic = (List<Citation>) knn.invoke(gateway, vector, orgTag, category, null);
            double knnMs = elapsed(start);
            start = System.nanoTime();
            var hybrid = gateway.hybridSearch(question, orgTag, category, vector);
            double hybridMs = elapsed(start);
            for (Citation citation : hybrid.citations()) assertTrue(frozen.containsKey(citation.chunkId()),
                    "Retrieved a chunk outside the self-authored document");
            ObjectNode row = rows.addObject();
            row.set("label", item);
            row.put("embeddingMs", embeddingMs);
            row.put("bm25Ms", bm25Ms);
            row.put("knnMs", knnMs);
            row.put("hybridEsMs", hybridMs);
            row.put("hybridWithEmbeddingMs", embeddingMs + hybridMs);
            row.set("bm25Candidates", json.valueToTree(lexical));
            row.set("knnCandidates", json.valueToTree(semantic));
            row.set("currentHybrid", json.valueToTree(hybrid.citations()));
            row.put("strategy", hybrid.strategy());
            json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("live-results.json").toFile(), report);
            System.out.printf("RAG_LIVE %s bm25=%d knn=%d hybrid=%d embeddingMs=%.1f esMs=%.1f%n",
                    item.path("id").asText(), lexical.size(), semantic.size(), hybrid.citations().size(), embeddingMs, hybridMs);
        }

        // Negative permission/scope probe must not see any Java snippets.
        List<Double> vector = embedding.embed("Redis 缓存击穿");
        var inaccessible = gateway.hybridSearch("Redis 缓存击穿", "__benchmark_absent_org__", category, vector);
        assertTrue(inaccessible.citations().isEmpty());
        report.put("absentOrgScopePassed", true);
        checkSnapshot(esUrl, index, orgTag, category, frozen);
        report.put("snapshotUnchangedAtEnd", true);
        report.put("completedAt", Instant.now().toString());
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("live-results.json").toFile(), report);
    }

    private void checkSnapshot(String url, String index, String org, String category,
                               Map<String, JsonNode> frozen) throws Exception {
        ObjectNode body = json.createObjectNode().put("size", 10000).put("track_total_hits", true);
        ArrayNode filters = body.putObject("query").putObject("bool").putArray("filter");
        filters.addObject().putObject("term").put("status", "READY");
        filters.addObject().putObject("term").put("orgTag", org);
        filters.addObject().putObject("term").put("category", category);
        body.putObject("_source").putArray("excludes").add("embedding");
        JsonNode response = es(url, index, body);
        assertEquals(frozen.size(), response.path("hits").path("total").path("value").asInt(), "Corpus changed");
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            JsonNode prior = frozen.get(source.path("chunkId").asText());
            assertNotNull(prior, "New chunk appeared during benchmark");
            assertEquals(prior.path("content").asText(), source.path("content").asText(), "Source content changed");
        }
    }

    private JsonNode es(String url, String index, ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/" + index + "/_search"))
                .timeout(java.time.Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Read-only ES snapshot query failed");
        return json.readTree(response.body());
    }

    private String value(Properties properties, String name, String fallback) {
        return System.getenv().getOrDefault(name, properties.getProperty(name, fallback));
    }

    private void requireLocal(String url) {
        assertTrue(Set.of("localhost", "127.0.0.1", "::1").contains(URI.create(url).getHost()),
                "This benchmark is limited to local ES/Qwen services");
    }

    private double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
}
