package com.lm.codeprep.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.knowledge.KnowledgeModels.Citation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Public web search is only called after the user explicitly opts in for this run. */
@Service
public class WebSearchClient {
    private final boolean enabled;
    private final String apiKey;
    private final int maxResults;
    private final ObjectMapper mapper;
    private final WebClient client;

    public WebSearchClient(@Value("${codeprep.web-search.enabled:false}") boolean enabled,
                           @Value("${codeprep.web-search.base-url}") String baseUrl,
                           @Value("${codeprep.web-search.api-key:}") String apiKey,
                           @Value("${codeprep.web-search.max-results:5}") int maxResults,
                           ObjectMapper mapper) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.maxResults = Math.max(1, Math.min(10, maxResults));
        this.mapper = mapper;
        this.client = WebClient.builder().baseUrl(baseUrl).build();
    }

    public List<Citation> search(String query, String category) {
        if (!enabled) throw new IllegalStateException("联网搜索未启用；设置 CODEPREP_WEB_SEARCH_ENABLED=true");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("联网搜索缺少 TAVILY_API_KEY");
        if (query == null || query.isBlank()) throw new IllegalArgumentException("搜索词不能为空");
        try {
            String response = client.post().uri("/search")
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .bodyValue(Map.of("query", query.substring(0, Math.min(300, query.length())),
                            "search_depth", "basic", "max_results", maxResults,
                            "include_answer", false, "include_raw_content", false))
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(12));
            JsonNode results = mapper.readTree(response).path("results");
            List<Citation> citations = new ArrayList<>();
            for (JsonNode result : results) {
                String url = result.path("url").asText("");
                URI uri;
                try { uri = URI.create(url); } catch (IllegalArgumentException ignored) { continue; }
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) continue;
                String content = result.path("content").asText("").strip();
                if (content.isEmpty()) continue;
                String title = result.path("title").asText(url);
                citations.add(new Citation("web-" + (citations.size() + 1), url,
                        title.substring(0, Math.min(200, title.length())),
                        content.substring(0, Math.min(1500, content.length())), category,
                        citations.size(), result.path("score").asDouble(0), "WEB_SEARCH", url));
                if (citations.size() >= maxResults) break;
            }
            return List.copyOf(citations);
        } catch (Exception exception) {
            throw new IllegalStateException("联网搜索失败，请检查 Tavily 配置或稍后重试", exception);
        }
    }
}
