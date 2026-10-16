package com.lm.codeprep.knowledge;

import com.lm.codeprep.persistence.entity.DocumentOutboxEntity;
import com.lm.codeprep.persistence.mapper.DocumentOutboxMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.lm.codeprep.knowledge.KnowledgeModels.StoredDocument;

/** Atomically records the accepted document and its RabbitMQ delivery intent. */
@Service
public class DocumentSubmissionService {
    private final KnowledgeStore store;
    private final DocumentOutboxMapper outbox;
    private final boolean rabbitEnabled;

    public DocumentSubmissionService(KnowledgeStore store, DocumentOutboxMapper outbox,
                                     @Value("${codeprep.rabbitmq.enabled:true}") boolean rabbitEnabled) {
        this.store = store;
        this.outbox = outbox;
        this.rabbitEnabled = rabbitEnabled;
    }

    @Transactional
    public StoredDocument accept(String id, String title, String category, String orgTag,
                                 String fileName, String groupName) {
        StoredDocument document = store.create(id, title, category, orgTag, fileName, groupName);
        if (rabbitEnabled) {
            long now = System.currentTimeMillis();
            DocumentOutboxEntity event = new DocumentOutboxEntity();
            event.documentId = id;
            event.status = "PENDING";
            event.attemptCount = 0;
            event.nextAttemptAt = now;
            event.createdAt = now;
            event.updatedAt = now;
            outbox.insert(event);
        }
        return document;
    }
}
