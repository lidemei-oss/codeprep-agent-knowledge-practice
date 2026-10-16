package com.lm.codeprep.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeepSeekClientTest {
    @Test
    void evidenceRequestUsesBoundedJsonWithoutChangingRegularChat() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        java.util.List<com.fasterxml.jackson.databind.JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        server.createContext("/chat/completions", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            DeepSeekClient client = new DeepSeekClient(true, "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-key", "deepseek-chat", .2, mapper);
            client.chatJson("Output JSON", "data").block();
            client.chat("Answer", "question").block();
            assertEquals("json_object", requests.get(0).path("response_format").path("type").asText());
            assertEquals(1024, requests.get(0).path("max_tokens").asInt());
            assertEquals(0, requests.get(0).path("temperature").asDouble());
            org.junit.jupiter.api.Assertions.assertFalse(requests.get(1).has("response_format"));
            assertEquals(.2, requests.get(1).path("temperature").asDouble());
        } finally { server.stop(0); }
    }

    @Test
    void decodesSseDataEventsIntoTextDeltas() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":\"Hash\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"Map\"}}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            DeepSeekClient client = new DeepSeekClient(true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key",
                    "deepseek-chat", 0.2, new ObjectMapper());
            assertEquals(List.of("Hash", "Map"), client.stream("system", "user").collectList().block());
        } finally { server.stop(0); }
    }
}
