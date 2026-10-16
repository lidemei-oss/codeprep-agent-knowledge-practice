package com.lm.codeprep.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class DeepSeekClient {
    private final boolean enabled;
    private final String model;
    private final double temperature;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final boolean officialDeepSeek;

    public DeepSeekClient(@Value("${codeprep.llm.enabled:false}") boolean enabled,
                          @Value("${codeprep.llm.base-url}") String baseUrl,
                          @Value("${codeprep.llm.api-key:}") String apiKey,
                          @Value("${codeprep.llm.model}") String model,
                          @Value("${codeprep.llm.temperature:0.2}") double temperature,
                          ObjectMapper mapper) {
        this.enabled = enabled;
        this.model = model;
        this.temperature = temperature;
        this.mapper = mapper;
        this.officialDeepSeek = "api.deepseek.com".equalsIgnoreCase(java.net.URI.create(baseUrl).getHost());
        this.client = WebClient.builder().baseUrl(baseUrl)
                .defaultHeaders(headers -> { if (apiKey != null && !apiKey.isBlank()) headers.setBearerAuth(apiKey); })
                .build();
    }

    public Mono<String> chat(String system, String user) {
        if (!enabled) return Mono.empty();
        return client.post().uri("/chat/completions").accept(MediaType.APPLICATION_JSON).bodyValue(request(system, user, false))
                .retrieve().bodyToMono(String.class).map(this::content);
    }

    public boolean enabled() { return enabled; }

    /** Bounded structured output for evidence verification; does not alter answer generation. */
    public Mono<String> chatJson(String system, String user) {
        if (!enabled) return Mono.empty();
        Map<String, Object> body = new java.util.HashMap<>(request(system, user, false));
        body.put("response_format", Map.of("type", "json_object"));
        body.put("max_tokens", 1024);
        body.put("temperature", 0);
        if (officialDeepSeek) body.put("thinking", Map.of("type", "disabled"));
        return client.post().uri("/chat/completions").accept(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(String.class).map(this::content);
    }

    public Flux<String> stream(String system, String user) {
        if (!enabled) return Flux.empty();
        return client.post().uri("/chat/completions").accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request(system, user, true)).retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { })
                .map(ServerSentEvent::data).filter(java.util.Objects::nonNull)
                .map(String::trim).filter(data -> !"[DONE]".equals(data))
                .map(this::delta).filter(value -> value != null && !value.isBlank());
    }

    private Map<String, Object> request(String system, String user, boolean stream) {
        return Map.of("model", model, "temperature", temperature, "stream", stream,
                "messages", java.util.List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
    }
    private String content(String json) { try { return mapper.readTree(json).path("choices").path(0).path("message").path("content").asText(); } catch (Exception e) { throw new IllegalStateException("DeepSeek 响应解析失败", e); } }
    private String delta(String json) { try { JsonNode node = mapper.readTree(json).path("choices").path(0).path("delta").path("content"); return node.isMissingNode() ? "" : node.asText(); } catch (Exception e) { return ""; } }
}
