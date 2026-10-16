package com.lm.codeprep.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class QwenEmbeddingClientTest {
    @Test
    void trimsMatryoshkaVectorTo2048AndNormalizesIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            StringBuilder json = new StringBuilder("{\"data\":[{\"embedding\":[");
            for (int i = 0; i < 2560; i++) json.append(i == 0 ? "1" : ",1");
            byte[] response = json.append("]}]} ").toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            QwenEmbeddingClient client = new QwenEmbeddingClient(true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key",
                    "Qwen3-Embedding-4B", 2048, new ObjectMapper());
            var vector = client.embed("Java");
            assertEquals(2048, vector.size());
            assertEquals(1.0, Math.sqrt(vector.stream().mapToDouble(value -> value * value).sum()), 0.00001);
        } finally { server.stop(0); }
    }
}
