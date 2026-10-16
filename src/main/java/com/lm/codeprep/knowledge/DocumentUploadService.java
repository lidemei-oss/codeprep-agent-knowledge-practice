package com.lm.codeprep.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static com.lm.codeprep.knowledge.KnowledgeModels.*;

@Service
public class DocumentUploadService {
    private final Path directory;
    private final DocumentSubmissionService submissions;
    private final DocumentIngestionService ingestion;
    private final boolean rabbitEnabled;

    public DocumentUploadService(@Value("${codeprep.upload.directory}") String directory,
                                 DocumentSubmissionService submissions, DocumentIngestionService ingestion,
                                 @Value("${codeprep.rabbitmq.enabled:true}") boolean rabbitEnabled) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.submissions = submissions; this.ingestion = ingestion; this.rabbitEnabled = rabbitEnabled;
        try { Files.createDirectories(this.directory); } catch (IOException exception) { throw new IllegalStateException("无法创建上传目录", exception); }
    }
    /** Saves the source file, then atomically records the document and its delivery intent. */
    public StoredDocument upload(MultipartFile file, String category, String orgTag, String groupName) {
        if (file.isEmpty()) throw new IllegalArgumentException("文件不能为空");
        String id = UUID.randomUUID().toString();
        String original = file.getOriginalFilename() == null ? "unnamed" : Path.of(file.getOriginalFilename()).getFileName().toString();
        Path target = directory.resolve(id + "-" + original);
        try { file.transferTo(target); } catch (IOException exception) { throw new IllegalStateException("保存文件失败", exception); }
        StoredDocument document = submissions.accept(id, original, category, orgTag, original, groupName);
        if (!rabbitEnabled) ingestion.processAsync(id, target);
        return document;
    }

    public StoredDocument registerCompleted(String id, String filename, Path path, String category, String orgTag,
                                            String groupName) {
        StoredDocument document = submissions.accept(id, filename, category, orgTag, filename, groupName);
        if (!rabbitEnabled) ingestion.processAsync(id, path);
        return document;
    }

    public Path pathOf(String documentId) {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith(documentId + "-"))
                    .findFirst().orElseThrow();
        } catch (IOException exception) { throw new IllegalStateException("找不到资料文件", exception); }
    }
}
