package com.lm.codeprep.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.knowledge.KnowledgeModels.Chunk;
import com.lm.codeprep.knowledge.KnowledgeStore;
import com.lm.codeprep.knowledge.EvidenceSelectionService;
import com.lm.codeprep.knowledge.KnowledgeModels.EvidenceSelection;
import com.lm.codeprep.knowledge.KnowledgeModels.EvidenceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"codeprep.elasticsearch.enabled=false", "codeprep.embedding.enabled=false",
        "codeprep.llm.enabled=false", "codeprep.rabbitmq.enabled=false", "codeprep.redis.enabled=false",
        "codeprep.web-search.api-key="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentHttpFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired KnowledgeStore store;
    @MockBean EvidenceSelectionService selection;

    @BeforeEach
    void verifiedEvidenceFixture() {
        when(selection.select(anyString(), anyList())).thenAnswer(call ->
                new EvidenceSelection(EvidenceStatus.SUPPORTED, call.getArgument(1), "TEST_VERIFIED"));
    }

    @Test
    void loginKnowledgeAnswerAndPracticeShareTheSameEvidence() throws Exception {
        String token = login("alice", "alice123");
        store.create("test-doc", "HashMap资料", "java", "java", "test.md");
        store.saveChunk(new Chunk("test-chunk", "test-doc", "HashMap资料",
                "HashMap 用数组和链表存储键值对，并处理哈希冲突。", "java", "java", 0, null));
        store.markReady("test-doc", 1);

        JsonNode knowledge = call(token, "{\"task\":\"解释 HashMap 的哈希冲突\",\"category\":\"java\",\"orgTag\":\"java\"}");
        assertEquals("COMPLETED", knowledge.path("status").asText());
        assertEquals("SEARCH_KNOWLEDGE", knowledge.path("intent").asText());
        assertTrue(knowledge.path("citations").size() > 0);
        assertTrue(knowledge.path("steps").size() >= 4);

        JsonNode exercise = call(token, "{\"task\":\"给我出一道 HashMap 刷题练习\",\"category\":\"java\",\"orgTag\":\"java\"}");
        assertEquals("START_PRACTICE", exercise.path("intent").asText());
        assertTrue(exercise.path("question").path("sourceChunkIds").size() > 0);

        String readerToken = login("reader", "reader123");
        mvc.perform(post("/api/agent/runs").header("Authorization", "Bearer " + readerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"解释 HashMap\",\"category\":\"java\",\"orgTag\":\"finance\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unansweredQuestionDefaultsToPublicSearchAndReportsMissingSearchKey() throws Exception {
        String token = login("alice", "alice123");
        JsonNode result = call(token,
                "{\"task\":\"Zeraphion QuantumFlux\",\"category\":\"java\",\"orgTag\":\"java\"}");

        assertEquals("WEB_SEARCH_UNAVAILABLE", result.path("status").asText());
        assertTrue(result.path("steps").toString().contains("SEARCH_PUBLIC_WEB"));
        assertTrue(result.path("steps").toString().contains("Tavily"));
    }

    @Test
    void documentServiceFiltersGroupsAndGuardsMoves() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        store.create(id, "Scoped", "java", "finance", "scoped.md", "private");
        store.markReady(id, 0);
        String reader = login("reader", "reader123");
        String owner = login("alice", "alice123");

        String readerDocuments = mvc.perform(get("/api/knowledge/documents")
                        .header("Authorization", "Bearer " + reader))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String readerGroups = mvc.perform(get("/api/knowledge/groups")
                        .header("Authorization", "Bearer " + reader))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(readerDocuments.contains(id));
        assertFalse(readerGroups.contains("finance"));

        mvc.perform(patch("/api/knowledge/documents/{documentId}/group", id)
                        .header("Authorization", "Bearer " + reader)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"groupName\":\"renamed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/knowledge/documents/{documentId}/group", id)
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"groupName\":\"renamed\"}"))
                .andExpect(status().isOk());
        assertEquals("renamed", store.requiredDocument(id).groupName());
    }

    private String login(String username, String password) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("username", username, "password", password));
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("token").asText();
    }

    private JsonNode call(String token, String body) throws Exception {
        String response = mvc.perform(post("/api/agent/runs").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
}
