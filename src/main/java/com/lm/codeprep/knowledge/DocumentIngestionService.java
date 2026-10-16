package com.lm.codeprep.knowledge;

import com.lm.codeprep.infra.ElasticsearchKnowledgeGateway;
import com.lm.codeprep.infra.QwenEmbeddingClient;
import jakarta.annotation.PostConstruct;
import org.apache.tika.Tika;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;

import java.io.InputStream;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.lm.codeprep.knowledge.KnowledgeModels.*;

@Service
@DependsOn("documentIngestionSchemaMigration")
public class DocumentIngestionService {
    private final KnowledgeStore store;
    private final QwenEmbeddingClient embedding;
    private final ElasticsearchKnowledgeGateway elasticsearch;
    private final StructuredDocumentChunker chunker;
    private final Tika tika = new Tika();

    public DocumentIngestionService(KnowledgeStore store, QwenEmbeddingClient embedding,
                                    ElasticsearchKnowledgeGateway elasticsearch, StructuredDocumentChunker chunker) {
        this.store = store; this.embedding = embedding; this.elasticsearch = elasticsearch; this.chunker = chunker;
    }

    @PostConstruct
    public void prepareIndex() { elasticsearch.ensureIndex(); }

    @Async
    public void processAsync(String documentId, Path file) { process(documentId, file); }

    public void process(String documentId, Path file) {
        try { processOrThrow(documentId, file); }
        catch (RuntimeException ignored) { }
    }

    public void processOrThrow(String documentId, Path file) {
        String workerToken = UUID.randomUUID().toString();
        if (!store.claimIngestion(documentId, workerToken)) return;
        try { processOrThrow(documentId, file, workerToken); }
        catch (RuntimeException exception) {
            store.markFailedOwned(documentId, workerToken);
            throw exception;
        }
    }

    /** The caller must atomically claim the document before entering this method. */
    public void processOrThrow(String documentId, Path file, String workerToken) {
        ensureOwnership(documentId, workerToken);
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "document-ingest-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try { store.renewIngestion(documentId, workerToken); }
            catch (RuntimeException ignored) { /* The worker checks ownership before further writes. */ }
        }, 30, 30, TimeUnit.SECONDS);
        try { ingestClaimed(documentId, file, workerToken); }
        finally { heartbeat.shutdownNow(); }
    }

    private void ingestClaimed(String documentId, Path file, String workerToken) {
        StoredDocument document = store.requiredDocument(documentId);
        try {
            String text = extractText(file);
            List<String> pieces = chunker.split(text, document.title());
            ensureOwnership(documentId, workerToken);
            store.clearDocumentChunks(documentId);
            elasticsearch.deleteDocument(documentId);
            int number = 0;
            for (String piece : pieces) {
                List<Double> vector = embedding.embed(piece);
                ensureOwnership(documentId, workerToken);
                Chunk chunk = new Chunk(documentId + "-" + number,
                        documentId, document.title(), piece, document.category(), document.orgTag(), number++, vector);
                store.saveChunk(chunk);
                elasticsearch.index(chunk);
            }
            ensureOwnership(documentId, workerToken);
            elasticsearch.markDocumentReady(documentId);
            if (!store.markReadyOwned(documentId, workerToken, number))
                throw new IllegalStateException("文档处理资格已失效：" + documentId);
        } catch (Exception exception) {
            if (store.ownsIngestion(documentId, workerToken)) {
                store.clearDocumentChunks(documentId);
                try { elasticsearch.deleteDocument(documentId); } catch (RuntimeException ignored) { }
            }
            throw new IllegalStateException("文档处理失败：" + documentId, exception);
        }
    }

    private void ensureOwnership(String documentId, String workerToken) {
        if (!store.ownsIngestion(documentId, workerToken))
            throw new IllegalStateException("文档处理资格已失效：" + documentId);
    }

    private String extractText(Path file) throws Exception {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (MalformedInputException ignored) {
                // 交给 Tika 处理其他编码的 Markdown。
            }
        }
        try (InputStream input = Files.newInputStream(file)) {
            return tika.parseToString(input);
        }
    }
}
