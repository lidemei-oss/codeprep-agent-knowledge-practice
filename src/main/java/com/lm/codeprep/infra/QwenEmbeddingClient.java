package com.lm.codeprep.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

@Service
public class QwenEmbeddingClient {
    private final boolean enabled;
    private final int dimension;
    private final String model;
    private final WebClient client;
    private final ObjectMapper mapper;

    public QwenEmbeddingClient(@Value("${codeprep.embedding.enabled:false}") boolean enabled,
                               @Value("${codeprep.embedding.base-url}") String baseUrl,
                               @Value("${codeprep.embedding.api-key:}") String apiKey,
                               @Value("${codeprep.embedding.model}") String model,
                               @Value("${codeprep.embedding.dimension:2048}") int dimension,
                               ObjectMapper mapper) {
        this.enabled = enabled;
        this.dimension = dimension;
        this.model = model;
        this.mapper = mapper;
        this.client = WebClient.builder().baseUrl(baseUrl)
                .defaultHeaders(headers -> { if (apiKey != null && !apiKey.isBlank()) headers.setBearerAuth(apiKey); })
                .build();
    }

    public List<Double> embed(String text) {
        if (!enabled) return null;
        try {
            String body = client.post().uri("/embeddings")
                    .bodyValue(java.util.Map.of("model", model, "input", text))
                    .retrieve().bodyToMono(String.class).block();
            JsonNode data = mapper.readTree(body).path("data").path(0).path("embedding");
            List<Double> vector = new ArrayList<>();
            for (JsonNode value : data) vector.add(value.asDouble());
            if (vector.size() > dimension && model.contains("Qwen3-Embedding-4B")) {
                vector = new ArrayList<>(vector.subList(0, dimension));
                double norm = Math.sqrt(vector.stream().mapToDouble(value -> value * value).sum());
                if (norm > 0) for (int i = 0; i < vector.size(); i++) vector.set(i, vector.get(i) / norm);
            }
            if (vector.size() != dimension) throw new IllegalStateException("Qwen Embedding 维度为 " + vector.size() + "，期望 " + dimension);
            return vector;
        } catch (Exception exception) {
            throw new IllegalStateException("Qwen Embedding 调用失败: " + exception.getMessage(), exception);
        }
    }
}
