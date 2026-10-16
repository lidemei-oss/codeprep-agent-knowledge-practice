package com.lm.codeprep.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lm.codeprep.persistence.entity.ChunkEntity;
import com.lm.codeprep.persistence.entity.DocumentEntity;
import com.lm.codeprep.persistence.mapper.ChunkMapper;
import com.lm.codeprep.persistence.mapper.DocumentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.lm.codeprep.knowledge.KnowledgeModels.*;

@Service
public class KnowledgeStore {
    public static final long INGEST_LEASE_MILLIS = java.util.concurrent.TimeUnit.MINUTES.toMillis(30);
    private final Map<String, StoredDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, Chunk> chunks = new ConcurrentHashMap<>();
    private final Map<String, IngestionClaim> ingestionClaims = new ConcurrentHashMap<>();
    private final Path metadataFile;
    private final ObjectMapper mapper;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;

    /** Isolated unit tests only; Spring injects the Mapper-backed constructor. */
    public KnowledgeStore() {
        this.metadataFile = null; this.mapper = null;
        this.documentMapper = null; this.chunkMapper = null;
    }

    @Autowired
    public KnowledgeStore(DocumentMapper documentMapper, ChunkMapper chunkMapper,
                          @Value("${codeprep.knowledge.metadata-file:./data/documents.json}") String metadataPath,
                          ObjectMapper mapper) {
        this.metadataFile = Path.of(metadataPath).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        importLegacy();
    }

    private void importLegacy() {
        if (documentMapper == null || metadataFile == null || !Files.exists(metadataFile)) return;
        try {
            for (StoredDocument document : mapper.readValue(metadataFile.toFile(), new TypeReference<List<StoredDocument>>() { })) {
                if (documentMapper.selectById(document.id()) == null) documentMapper.insert(toEntity(document));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("旧文档元数据导入 MySQL 失败：" + metadataFile, exception);
        }
    }

    public synchronized StoredDocument create(String id, String title, String category, String orgTag, String fileName,
                                              String groupName) {
        StoredDocument document = new StoredDocument(id, title, category, orgTag, "PROCESSING", fileName,
                Instant.now(), 0, groupName);
        if (documentMapper == null) documents.put(id, document);
        else documentMapper.insert(toEntity(document));
        return document;
    }

    public StoredDocument create(String id, String title, String category, String orgTag, String fileName) {
        return create(id, title, category, orgTag, fileName, DEFAULT_GROUP);
    }

    public void markReady(String id, int chunkCount) { updateStatus(id, "READY", chunkCount); }
    public void markFailed(String id) { updateStatus(id, "FAILED", 0); }

    /** A conditional UPDATE (or synchronized in-memory equivalent) grants one worker ownership before cleanup. */
    public synchronized boolean claimIngestion(String id, String token) {
        long now = System.currentTimeMillis();
        long until = now + INGEST_LEASE_MILLIS;
        if (documentMapper != null) return documentMapper.claimIngestion(id, token, now, until) == 1;
        StoredDocument document = documents.get(id);
        IngestionClaim old = ingestionClaims.get(id);
        if (document == null || !"PROCESSING".equals(document.status())
                || old != null && old.leaseUntil() > now) return false;
        ingestionClaims.put(id, new IngestionClaim(token, until));
        return true;
    }

    public synchronized boolean renewIngestion(String id, String token) {
        long now = System.currentTimeMillis();
        long until = now + INGEST_LEASE_MILLIS;
        if (documentMapper != null) return documentMapper.renewIngestion(id, token, now, until) == 1;
        if (!ownsIngestion(id, token)) return false;
        ingestionClaims.put(id, new IngestionClaim(token, until));
        return true;
    }

    public synchronized boolean ownsIngestion(String id, String token) {
        long now = System.currentTimeMillis();
        if (documentMapper != null) return documentMapper.ownsIngestion(id, token, now) == 1;
        IngestionClaim claim = ingestionClaims.get(id);
        StoredDocument document = documents.get(id);
        return document != null && "PROCESSING".equals(document.status()) && claim != null
                && token.equals(claim.token()) && claim.leaseUntil() > now;
    }

    public synchronized boolean markReadyOwned(String id, String token, int chunkCount) {
        if (documentMapper != null)
            return documentMapper.markReadyOwned(id, token, chunkCount, System.currentTimeMillis()) == 1;
        if (!ownsIngestion(id, token)) return false;
        updateStatus(id, "READY", chunkCount);
        ingestionClaims.remove(id);
        return true;
    }

    public synchronized boolean markFailedOwned(String id, String token) {
        if (documentMapper != null)
            return documentMapper.markFailedOwned(id, token, System.currentTimeMillis()) == 1;
        if (!ownsIngestion(id, token)) return false;
        updateStatus(id, "FAILED", 0);
        ingestionClaims.remove(id);
        return true;
    }

    public synchronized boolean markFailedUnclaimed(String id) {
        if (documentMapper != null) return documentMapper.markFailedUnclaimed(id) == 1;
        StoredDocument document = documents.get(id);
        if (document == null || !"PROCESSING".equals(document.status()) || ingestionClaims.containsKey(id)) return false;
        updateStatus(id, "FAILED", 0);
        return true;
    }

    private synchronized void updateStatus(String id, String status, int count) {
        if (documentMapper == null) {
            documents.computeIfPresent(id, (key, old) -> new StoredDocument(old.id(), old.title(), old.category(),
                    old.orgTag(), status, old.fileName(), old.createdAt(), count, old.groupName()));
        } else {
            DocumentEntity entity = documentMapper.selectById(id);
            if (entity == null) throw new IllegalArgumentException("资料不存在");
            entity.status = status;
            entity.chunkCount = count;
            documentMapper.updateById(entity);
        }
    }

    public synchronized StoredDocument moveGroup(String id, String groupName) {
        StoredDocument old = requiredDocument(id);
        StoredDocument updated = new StoredDocument(old.id(), old.title(), old.category(), old.orgTag(),
                old.status(), old.fileName(), old.createdAt(), old.chunkCount(), groupName);
        if (documentMapper == null) documents.put(id, updated);
        else documentMapper.updateById(toEntity(updated));
        return updated;
    }

    public void saveChunk(Chunk chunk) {
        if (chunkMapper == null) chunks.put(chunk.id(), chunk);
        else chunkMapper.insert(toEntity(chunk));
    }
    public List<StoredDocument> documents() {
        List<StoredDocument> result = documentMapper == null ? new ArrayList<>(documents.values())
                : documentMapper.selectList(null).stream().map(this::fromEntity).toList();
        return result.stream().sorted(Comparator.comparing(StoredDocument::createdAt).reversed()).toList();
    }
    public StoredDocument requiredDocument(String id) {
        StoredDocument found = documentMapper == null ? documents.get(id) : fromEntity(documentMapper.selectById(id));
        return java.util.Optional.ofNullable(found).orElseThrow(() -> new IllegalArgumentException("资料不存在"));
    }
    public List<Chunk> chunks(String orgTag, String category) {
        return chunks(orgTag, category, null);
    }
    public List<Chunk> chunks(String orgTag, String category, String groupName) {
        String selectedGroup = groupName == null ? null : normalizeGroup(groupName);
        if (chunkMapper != null)
            return chunkMapper.selectReady(orgTag, category, selectedGroup).stream().map(this::fromEntity).toList();
        Map<String, StoredDocument> available = documents().stream().collect(Collectors.toMap(StoredDocument::id, d -> d));
        List<Chunk> source = new ArrayList<>(chunks.values());
        return source.stream().filter(c -> available.containsKey(c.documentId()))
                .filter(c -> "READY".equals(available.get(c.documentId()).status()))
                .filter(c -> orgTag == null || orgTag.isBlank() || orgTag.equals(c.orgTag()))
                .filter(c -> category == null || category.isBlank() || category.equals(c.category()))
                .filter(c -> selectedGroup == null || selectedGroup.equals(available.get(c.documentId()).groupName())).toList();
    }
    public Set<String> readyDocumentIds(String orgTag, String category, String groupName) {
        String selectedGroup = normalizeGroup(groupName);
        if (documentMapper != null) {
            return documentMapper.selectList(new QueryWrapper<DocumentEntity>()
                            .eq("status", "READY").eq("org_tag", orgTag)
                            .eq("category", category).eq("group_name", selectedGroup))
                    .stream().map(d -> d.id).collect(Collectors.toSet());
        }
        return documents().stream().filter(d -> "READY".equals(d.status()))
                .filter(d -> orgTag.equals(d.orgTag()) && category.equals(d.category()))
                .filter(d -> selectedGroup.equals(d.groupName())).map(StoredDocument::id).collect(Collectors.toSet());
    }
    public List<GroupView> groups() {
        return documents().stream().collect(Collectors.groupingBy(d -> new GroupKey(d.category(), d.orgTag(), d.groupName())))
                .entrySet().stream().map(entry -> new GroupView(entry.getKey().category(), entry.getKey().orgTag(),
                        entry.getKey().groupName(), entry.getValue().size(),
                        entry.getValue().stream().filter(d -> "READY".equals(d.status())).count()))
                .sorted(Comparator.comparing(GroupView::orgTag).thenComparing(GroupView::category)
                        .thenComparing(GroupView::groupName)).toList();
    }
    public List<Chunk> documentChunks(String documentId) {
        List<Chunk> source = chunkMapper == null ? new ArrayList<>(chunks.values())
                : chunkMapper.selectList(new QueryWrapper<ChunkEntity>().eq("document_id", documentId))
                        .stream().map(this::fromEntity).toList();
        return source.stream().filter(c -> c.documentId().equals(documentId))
                .sorted(Comparator.comparingInt(Chunk::chunkNo)).toList();
    }
    public void clearDocumentChunks(String documentId) {
        if (chunkMapper == null) chunks.values().removeIf(c -> c.documentId().equals(documentId));
        else chunkMapper.delete(new QueryWrapper<ChunkEntity>().eq("document_id", documentId));
    }
    public List<Citation> lexicalSearch(String query, String orgTag, String category, int limit) {
        return lexicalSearch(query, orgTag, category, null, limit);
    }
    public List<Citation> lexicalSearch(String query, String orgTag, String category, String groupName, int limit) {
        String normalized = normalize(query);
        return chunks(orgTag, category, groupName).stream().map(chunk -> {
            int score = 0;
            String haystack = normalize(chunk.title() + " " + chunk.content());
            for (String token : normalized.split(" ")) if (token.length() > 1 && haystack.contains(token)) score += token.length();
            return Map.entry(chunk, score);
        }).filter(entry -> entry.getValue() > 0).sorted(Map.Entry.<Chunk, Integer>comparingByValue().reversed())
                .limit(limit).map(entry -> new Citation(entry.getKey().id(), entry.getKey().documentId(), entry.getKey().title(),
                        entry.getKey().content(), entry.getKey().category(), entry.getKey().chunkNo(), entry.getValue(), keywordSource())).toList();
    }

    public String keywordSource() { return chunkMapper == null ? "IN_MEMORY_KEYWORD" : "MYSQL_KEYWORD"; }

    private DocumentEntity toEntity(StoredDocument document) {
        DocumentEntity entity = new DocumentEntity();
        entity.id = document.id(); entity.title = document.title(); entity.category = document.category();
        entity.orgTag = document.orgTag(); entity.status = document.status(); entity.fileName = document.fileName();
        entity.createdAt = document.createdAt().toEpochMilli(); entity.chunkCount = document.chunkCount();
        entity.groupName = document.groupName();
        return entity;
    }

    private StoredDocument fromEntity(DocumentEntity entity) {
        return entity == null ? null : new StoredDocument(entity.id, entity.title, entity.category, entity.orgTag,
                entity.status, entity.fileName, Instant.ofEpochMilli(entity.createdAt), entity.chunkCount, entity.groupName);
    }

    private ChunkEntity toEntity(Chunk chunk) {
        ChunkEntity entity = new ChunkEntity();
        entity.id = chunk.id(); entity.documentId = chunk.documentId(); entity.title = chunk.title();
        entity.content = chunk.content(); entity.category = chunk.category(); entity.orgTag = chunk.orgTag();
        entity.chunkNo = chunk.chunkNo();
        return entity;
    }

    private Chunk fromEntity(ChunkEntity entity) {
        return new Chunk(entity.id, entity.documentId, entity.title, entity.content, entity.category,
                entity.orgTag, entity.chunkNo, null);
    }

    private record GroupKey(String category, String orgTag, String groupName) { }
    private record IngestionClaim(String token, long leaseUntil) { }
    private String normalize(String value) { return value == null ? "" : value.toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim(); }
}
