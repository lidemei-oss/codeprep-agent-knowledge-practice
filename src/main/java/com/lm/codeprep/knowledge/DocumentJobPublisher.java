package com.lm.codeprep.knowledge;

import com.lm.codeprep.persistence.entity.DocumentOutboxEntity;
import com.lm.codeprep.persistence.mapper.DocumentOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Retries committed outbox events until RabbitMQ confirms they reached a queue. */
@Service
@ConditionalOnProperty(name = "codeprep.rabbitmq.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentJobPublisher {
    private static final Logger log = LoggerFactory.getLogger(DocumentJobPublisher.class);
    private final DocumentOutboxMapper outbox;
    private final KnowledgeStore store;
    private final ObjectProvider<RabbitTemplate> template;
    private final String exchange;
    private final String routingKey;
    private final long leaseMs;
    private final long retryBaseMs;
    private final long retryMaxMs;

    public DocumentJobPublisher(DocumentOutboxMapper outbox, KnowledgeStore store, ObjectProvider<RabbitTemplate> template,
                                @Value("${codeprep.rabbitmq.exchange}") String exchange,
                                @Value("${codeprep.rabbitmq.routing-key}") String routingKey,
                                @Value("${codeprep.rabbitmq.outbox.lease-ms:30000}") long leaseMs,
                                @Value("${codeprep.rabbitmq.outbox.retry-base-ms:1000}") long retryBaseMs,
                                @Value("${codeprep.rabbitmq.outbox.retry-max-ms:300000}") long retryMaxMs) {
        this.outbox = outbox; this.store = store; this.template = template;
        this.exchange = exchange; this.routingKey = routingKey;
        this.leaseMs = Math.max(15000, leaseMs);
        this.retryBaseMs = Math.max(100, retryBaseMs);
        this.retryMaxMs = Math.max(this.retryBaseMs, retryMaxMs);
    }

    @Scheduled(fixedDelayString = "${codeprep.rabbitmq.outbox.poll-ms:2000}",
            initialDelayString = "${codeprep.rabbitmq.outbox.initial-delay-ms:1000}")
    public void dispatchDue() {
        long now = System.currentTimeMillis();
        for (DocumentOutboxEntity event : outbox.due(now, 20)) {
            String token = UUID.randomUUID().toString();
            if (outbox.claim(event.documentId, token, now, now + leaseMs) != 1) continue;
            try {
                String documentStatus = store.requiredDocument(event.documentId).status();
                if ("READY".equals(documentStatus) || "FAILED".equals(documentStatus)) {
                    outbox.cancel(event.documentId, token, System.currentTimeMillis());
                    continue;
                }
                RabbitTemplate producer = template.getIfAvailable();
                if (producer == null) throw new IllegalStateException("RabbitMQ 生产者未配置");
                CorrelationData correlation = new CorrelationData(event.documentId + ":" + token);
                producer.convertAndSend(exchange, routingKey, event.documentId, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
                if (!confirm.isAck() || correlation.getReturned() != null)
                    throw new IllegalStateException("RabbitMQ 未确认或无法路由文档任务");
                if (outbox.sent(event.documentId, token, System.currentTimeMillis()) != 1)
                    log.warn("文档任务已投递但 Outbox 租约已变化 documentId={}", event.documentId);
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                long failedAt = System.currentTimeMillis();
                int attempts = event.attemptCount == null ? 1 : event.attemptCount + 1;
                long delay = Math.min(retryMaxMs, retryBaseMs * (1L << Math.min(20, attempts - 1)));
                String error = String.valueOf(exception.getMessage());
                if (error.length() > 900) error = error.substring(0, 900);
                outbox.retry(event.documentId, token, failedAt + delay, error, failedAt);
                log.warn("RabbitMQ 投递失败，Outbox 将重试 documentId={} attempt={} delayMs={} cause={}",
                        event.documentId, attempts, delay, error);
            }
        }
    }
}
