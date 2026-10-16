package com.lm.codeprep.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchClientTest {
    @Test
    void parsesPublicSourcesAndSkipsInvalidUrls() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/search", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"results\":[{\"title\":\"bad\",\"url\":\"file:///secret\",\"content\":\"no\"},"
                    + "{\"title\":\"Java 文档\",\"url\":\"https://example.org/java\",\"content\":\"公开技术说明\",\"score\":0.8}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            WebSearchClient client = new WebSearchClient(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-key", 5, new ObjectMapper());
            var sources = client.search("Java 新功能", "java");
            assertEquals(1, sources.size());
            assertEquals("WEB_SEARCH", sources.get(0).retrievalSource());
            assertEquals("https://example.org/java", sources.get(0).url());
            assertEquals("Bearer test-key", authorization.get());
            assertTrue(requestBody.get().contains("Java 新功能"));
        } finally { server.stop(0); }
    }

    @Test
    void requiresConfigurationBeforeAnyExternalCall() {
        WebSearchClient client = new WebSearchClient(false, "https://api.tavily.com", "", 5, new ObjectMapper());
        assertThrows(IllegalStateException.class, () -> client.search("Java", "java"));
    }
}
