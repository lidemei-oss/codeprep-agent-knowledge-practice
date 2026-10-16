package com.lm.codeprep.knowledge;

import com.lm.codeprep.persistence.mapper.DocumentOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;

/** Backfills documents accepted by an older process that crashed before publishing. */
@Component
@ConditionalOnProperty(name = "codeprep.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentJobReconciler {
    private static final Logger log = LoggerFactory.getLogger(DocumentJobReconciler.class);
    private final DocumentOutboxMapper outbox;
    private final DocumentUploadService uploads;
    private final KnowledgeStore store;
    private final long staleMs;

    public DocumentJobReconciler(DocumentOutboxMapper outbox, DocumentUploadService uploads, KnowledgeStore store,
                                 @Value("${codeprep.rabbitmq.outbox.stale-ms:1800000}") long staleMs) {
        this.outbox = outbox; this.uploads = uploads; this.store = store;
        this.staleMs = Math.max(60000, staleMs);
    }

    @Scheduled(fixedDelayString = "${codeprep.rabbitmq.outbox.reconcile-ms:60000}",
            initialDelayString = "${codeprep.rabbitmq.outbox.reconcile-ms:60000}")
    public void backfillMissing() {
        long now = System.currentTimeMillis();
        for (String documentId : outbox.staleWithoutOutbox(now - staleMs, 20)) {
            try {
                uploads.pathOf(documentId);
                if (outbox.insertMissing(documentId, now) == 1)
                    log.warn("补建滞留文档的 Outbox 任务 documentId={}", documentId);
            } catch (NoSuchElementException exception) {
                if (store.markFailedUnclaimed(documentId))
                    log.error("滞留文档原文件丢失，已标记失败 documentId={}", documentId);
            } catch (RuntimeException exception) {
                log.warn("滞留文档对账失败 documentId={} cause={}", documentId, exception.toString());
            }
        }
        for (String documentId : outbox.expiredIngestionClaims(now, 20)) {
            if (outbox.requeueExpiredIngestion(documentId, now) == 1)
                log.warn("文档消费租约过期，已重新入队 documentId={}", documentId);
        }
    }
}
