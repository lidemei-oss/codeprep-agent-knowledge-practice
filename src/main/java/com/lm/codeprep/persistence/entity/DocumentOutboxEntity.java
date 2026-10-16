package com.lm.codeprep.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("cp_document_outbox")
public class DocumentOutboxEntity {
    @TableId(value = "document_id", type = IdType.INPUT)
    public String documentId;
    public String status;
    public Integer attemptCount;
    public Long nextAttemptAt;
    public Long leaseUntil;
    public String claimToken;
    public String lastError;
    public Long createdAt;
    public Long updatedAt;
    public Long sentAt;
}
