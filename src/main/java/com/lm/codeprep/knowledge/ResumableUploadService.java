package com.lm.codeprep.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.lm.codeprep.knowledge.KnowledgeModels.StoredDocument;

@Service
public class ResumableUploadService {
    private final Path directory;
    private final DocumentUploadService documents;
    private final Map<String, UploadState> uploads = new ConcurrentHashMap<>();

    public ResumableUploadService(@Value("${codeprep.upload.directory}") String directory, DocumentUploadService documents) {
        this.directory = Path.of(directory).toAbsolutePath().normalize(); this.documents = documents;
    }

    public String begin(String username, String filename, int totalChunks, long totalSize, String category, String orgTag,
                        String groupName) {
        if (totalChunks < 1 || totalChunks > 10_000) throw new IllegalArgumentException("分片数量不合法");
        if (totalSize < 1 || totalSize > 500_000_000L) throw new IllegalArgumentException("文件大小不合法");
        String id = UUID.randomUUID().toString();
        uploads.put(id, new UploadState(username, id, Path.of(filename).getFileName().toString(), totalChunks, totalSize,
                category, orgTag, KnowledgeModels.normalizeGroup(groupName)));
        return id;
    }

    public void writeChunk(String username, String uploadId, int index, byte[] bytes) {
        UploadState state = required(username, uploadId);
        if (index < 0 || index >= state.totalChunks) throw new IllegalArgumentException("分片序号不合法");
        if (bytes.length == 0 || bytes.length > 5_242_880) throw new IllegalArgumentException("分片大小不合法");
        try { Files.write(directory.resolve(uploadId + ".part." + index), bytes); state.received.put(index, Boolean.TRUE); }
        catch (IOException exception) { throw new IllegalStateException("分片保存失败", exception); }
    }

    public StoredDocument complete(String username, String uploadId) {
        UploadState state = required(username, uploadId);
        if (state.received.size() != state.totalChunks) throw new IllegalStateException("分片尚未全部上传");
        Path merged = directory.resolve(uploadId + "-" + state.filename);
        try (OutputStream output = Files.newOutputStream(merged)) {
            for (int i = 0; i < state.totalChunks; i++) Files.copy(directory.resolve(uploadId + ".part." + i), output);
            if (state.totalSize > 0 && Files.size(merged) != state.totalSize) throw new IllegalStateException("合并文件大小校验失败");
            for (int i = 0; i < state.totalChunks; i++) Files.deleteIfExists(directory.resolve(uploadId + ".part." + i));
        } catch (IOException exception) { throw new IllegalStateException("合并分片失败", exception); }
        uploads.remove(uploadId);
        return documents.registerCompleted(uploadId, state.filename, merged, state.category, state.orgTag, state.groupName);
    }

    private UploadState required(String username, String id) {
        UploadState state = java.util.Optional.ofNullable(uploads.get(id)).orElseThrow(() -> new IllegalArgumentException("上传任务不存在"));
        if (!state.username.equals(username)) throw new SecurityException("上传任务归属不匹配");
        return state;
    }
    private static final class UploadState {
        private final String username, id, filename, category, orgTag, groupName; private final int totalChunks; private final long totalSize;
        private final Map<Integer, Boolean> received = new ConcurrentHashMap<>();
        private UploadState(String username, String id, String filename, int totalChunks, long totalSize, String category,
                            String orgTag, String groupName) {
            this.username = username; this.id = id; this.filename = filename; this.totalChunks = totalChunks;
            this.totalSize = totalSize; this.category = category; this.orgTag = orgTag; this.groupName = groupName;
        }
    }
}
