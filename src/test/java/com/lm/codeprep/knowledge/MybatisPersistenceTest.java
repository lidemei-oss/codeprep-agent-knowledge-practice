package com.lm.codeprep.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.agent.AgentModels.RunResult;
import com.lm.codeprep.agent.AgentRunStore;
import com.lm.codeprep.infra.DeepSeekClient;
import com.lm.codeprep.persistence.mapper.AccountMapper;
import com.lm.codeprep.persistence.mapper.AgentRunMapper;
import com.lm.codeprep.persistence.mapper.ChunkMapper;
import com.lm.codeprep.persistence.mapper.DocumentMapper;
import com.lm.codeprep.persistence.mapper.PracticeSessionMapper;
import com.lm.codeprep.practice.PracticeAgentService;
import com.lm.codeprep.practice.PracticeModels.AnswerRequest;
import com.lm.codeprep.practice.PracticeModels.StartRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"codeprep.elasticsearch.enabled=false", "codeprep.embedding.enabled=false",
        "codeprep.llm.enabled=false", "codeprep.rabbitmq.enabled=false", "codeprep.redis.enabled=false"})
@ActiveProfiles("test")
class MybatisPersistenceTest {
    @Autowired KnowledgeStore store;
    @Autowired KnowledgeSearchService search;
    @Autowired PracticeAgentService practice;
    @Autowired DeepSeekClient llm;
    @Autowired ObjectMapper json;
    @Autowired DocumentMapper documents;
    @Autowired ChunkMapper chunks;
    @Autowired PracticeSessionMapper practiceSessions;
    @Autowired AgentRunMapper runs;
    @Autowired AccountMapper accounts;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void documentGroupAndChunksAreStoredInMySqlLayer() {
        String id = UUID.randomUUID().toString();
        store.create(id, "Redis知识", "java", "java", "redis.md", "缓存");
        store.saveChunk(new KnowledgeModels.Chunk(id + "-0", id, "Redis知识", "Redis 缓存击穿的原因与解决方案",
                "java", "java", 0, null));
        store.markReady(id, 1);

        assertEquals("READY", documents.selectById(id).status);
        assertEquals(id, chunks.selectById(id + "-0").documentId);
        assertEquals(List.of(id + "-0"), store.documentChunks(id).stream().map(KnowledgeModels.Chunk::id).toList());
        assertTrue(store.readyDocumentIds("java", "java", "缓存").contains(id));
        store.moveGroup(id, "分布式");
        assertEquals("分布式", documents.selectById(id).groupName);
        assertTrue(store.chunks("java", "java", "分布式").stream().anyMatch(c -> c.documentId().equals(id)));
    }

    @Test
    void practiceAndAgentRunsCanBeReadByNewServiceInstances() {
        String id = UUID.randomUUID().toString();
        store.create(id, "Java并发", "java", "java", "concurrency.md", "并发");
        store.saveChunk(new KnowledgeModels.Chunk(id + "-0", id, "Java并发", "Java 线程池使用有界队列控制任务积压",
                "java", "java", 0, null));
        store.markReady(id, 1);
        assertEquals("MYSQL_KEYWORD", search.retrieve("线程池", "java", "java", "并发").strategy());

        var question = practice.start("alice", new StartRequest("java", "java", 1, "medium", "并发"));
        var submission = new AnswerRequest(question.id(), UUID.randomUUID().toString(), "使用有界队列控制积压");
        var result = practice.answer("alice", question.sessionId(), submission);
        assertNotNull(practiceSessions.selectById(question.sessionId()));
        PracticeAgentService reloaded = new PracticeAgentService(search, llm, json, practiceSessions, transactionManager);
        assertEquals(1, reloaded.session("alice", question.sessionId()).completed());
        assertEquals(1, reloaded.reviewData("alice", question.sessionId()).attempts().size());
        assertEquals(result.score(), reloaded.reviewData("alice", question.sessionId()).attempts().get(0).score());
        assertEquals(result, reloaded.answer("alice", question.sessionId(), submission));

        String runId = UUID.randomUUID().toString();
        RunResult run = new RunResult(runId, "alice:java:" + UUID.randomUUID(), "START_PRACTICE",
                "COMPLETED", null, question, null, List.of(), List.of(), Instant.now());
        AgentRunStore writer = new AgentRunStore(runs, json);
        writer.save("alice", run);
        assertEquals(runId, new AgentRunStore(runs, json).get("alice", runId).runId());
        assertNotNull(accounts.selectById("alice"));
    }

    @Test
    void legacyJsonIsImportedOnceWithoutDeletingTheFile(@TempDir Path temp) throws Exception {
        String id = UUID.randomUUID().toString();
        Path oldFile = temp.resolve("documents.json");
        var old = new KnowledgeModels.StoredDocument(id, "旧资料", "java", "java", "READY",
                "legacy.md", Instant.now(), 2, "默认分组");
        Files.writeString(oldFile, json.writeValueAsString(List.of(old)));

        new KnowledgeStore(documents, chunks, oldFile.toString(), json);
        new KnowledgeStore(documents, chunks, oldFile.toString(), json);

        assertEquals("旧资料", documents.selectById(id).title);
        assertEquals(1, documents.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                com.lm.codeprep.persistence.entity.DocumentEntity>().eq("id", id)));
        assertTrue(Files.exists(oldFile));
    }
}
