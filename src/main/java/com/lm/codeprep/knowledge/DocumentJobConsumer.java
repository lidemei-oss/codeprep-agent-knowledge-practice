package com.lm.codeprep.knowledge;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "codeprep.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentJobConsumer {
    private static final Logger log = LoggerFactory.getLogger(DocumentJobConsumer.class);
    private final DocumentIngestionService ingestion;
    private final DocumentUploadService uploads;
    private final KnowledgeStore store;
    private final int maxAttempts;

    public DocumentJobConsumer(DocumentIngestionService ingestion, DocumentUploadService uploads,
                               KnowledgeStore store,
                               @Value("${codeprep.rabbitmq.max-attempts:3}") int maxAttempts) {
        this.ingestion = ingestion; this.uploads = uploads; this.store = store;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @RabbitListener(queues = "${codeprep.rabbitmq.queue}")
    public void consume(String documentId, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
            throws IOException {
        if ("READY".equals(store.requiredDocument(documentId).status())) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        String workerToken = UUID.randomUUID().toString();
        if (!store.claimIngestion(documentId, workerToken)) {
            // Another delivery owns this document; it must not reach the cleanup in ingestion.
            log.info("跳过已被其他消费者领取的文档任务 documentId={}", documentId);
            channel.basicAck(deliveryTag, false);
            return;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ingestion.processOrThrow(documentId, uploads.pathOf(documentId), workerToken);
                channel.basicAck(deliveryTag, false);
                return;
            } catch (RuntimeException exception) {
                if (!store.ownsIngestion(documentId, workerToken)) {
                    log.warn("旧消费者已失去文档处理权，跳过重试 documentId={}", documentId);
                    channel.basicAck(deliveryTag, false);
                    return;
                }
                Throwable rootCause = exception;
                while (rootCause.getCause() != null) rootCause = rootCause.getCause();
                log.warn("文档任务处理失败 documentId={} attempt={}/{} cause={}",
                        documentId, attempt, maxAttempts, rootCause.toString());
                if (attempt == maxAttempts) {
                    try { store.markFailedOwned(documentId, workerToken); }
                    finally { channel.basicNack(deliveryTag, false, false); }
                    return;
                }
            }
        }
    }
}
