package com.lm.codeprep.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lm.codeprep.persistence.entity.DocumentOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface DocumentOutboxMapper extends BaseMapper<DocumentOutboxEntity> {
    @Select("""
            SELECT document_id, status, attempt_count, next_attempt_at, lease_until, claim_token,
                   last_error, created_at, updated_at, sent_at
            FROM cp_document_outbox
            WHERE (status = 'PENDING' AND next_attempt_at <= #{now})
               OR (status = 'SENDING' AND lease_until <= #{now})
            ORDER BY next_attempt_at, created_at
            LIMIT #{limit}
            """)
    List<DocumentOutboxEntity> due(@Param("now") long now, @Param("limit") int limit);

    @Update("""
            UPDATE cp_document_outbox
            SET status = 'SENDING', claim_token = #{token}, lease_until = #{leaseUntil},
                attempt_count = attempt_count + 1, updated_at = #{now}
            WHERE document_id = #{id}
              AND ((status = 'PENDING' AND next_attempt_at <= #{now})
                OR (status = 'SENDING' AND lease_until <= #{now}))
            """)
    int claim(@Param("id") String id, @Param("token") String token,
              @Param("now") long now, @Param("leaseUntil") long leaseUntil);

    @Update("""
            UPDATE cp_document_outbox
            SET status = 'SENT', claim_token = NULL, lease_until = NULL,
                last_error = NULL, sent_at = #{now}, updated_at = #{now}
            WHERE document_id = #{id} AND status = 'SENDING' AND claim_token = #{token}
            """)
    int sent(@Param("id") String id, @Param("token") String token, @Param("now") long now);

    @Update("""
            UPDATE cp_document_outbox
            SET status = 'CANCELLED', claim_token = NULL, lease_until = NULL, updated_at = #{now}
            WHERE document_id = #{id} AND status = 'SENDING' AND claim_token = #{token}
            """)
    int cancel(@Param("id") String id, @Param("token") String token, @Param("now") long now);

    @Update("""
            UPDATE cp_document_outbox
            SET status = 'PENDING', claim_token = NULL, lease_until = NULL,
                next_attempt_at = #{nextAttemptAt}, last_error = #{error}, updated_at = #{now}
            WHERE document_id = #{id} AND status = 'SENDING' AND claim_token = #{token}
            """)
    int retry(@Param("id") String id, @Param("token") String token,
              @Param("nextAttemptAt") long nextAttemptAt, @Param("error") String error,
              @Param("now") long now);

    @Select("""
            SELECT d.id FROM cp_document d
            LEFT JOIN cp_document_outbox o ON o.document_id = d.id
            WHERE d.status = 'PROCESSING' AND d.created_at <= #{before}
              AND o.document_id IS NULL
            ORDER BY d.created_at
            LIMIT #{limit}
            """)
    List<String> staleWithoutOutbox(@Param("before") long before, @Param("limit") int limit);

    @Insert("""
            INSERT INTO cp_document_outbox
                (document_id, status, attempt_count, next_attempt_at, created_at, updated_at)
            SELECT d.id, 'PENDING', 0, #{now}, #{now}, #{now}
            FROM cp_document d
            WHERE d.id = #{id} AND d.status = 'PROCESSING'
              AND NOT EXISTS (SELECT 1 FROM cp_document_outbox o WHERE o.document_id = d.id)
            """)
    int insertMissing(@Param("id") String id, @Param("now") long now);

    @Select("""
            SELECT d.id FROM cp_document d
            JOIN cp_document_outbox o ON o.document_id = d.id
            WHERE d.status = 'PROCESSING' AND d.ingest_owner IS NOT NULL
              AND d.ingest_lease_until <= #{now} AND o.status = 'SENT'
            ORDER BY d.ingest_lease_until LIMIT #{limit}
            """)
    List<String> expiredIngestionClaims(@Param("now") long now, @Param("limit") int limit);

    @Update("""
            UPDATE cp_document_outbox
            SET status = 'PENDING', next_attempt_at = #{now}, updated_at = #{now},
                last_error = 'Document ingestion lease expired'
            WHERE document_id = #{id} AND status = 'SENT'
              AND EXISTS (SELECT 1 FROM cp_document d WHERE d.id = #{id}
                  AND d.status = 'PROCESSING' AND d.ingest_owner IS NOT NULL
                  AND d.ingest_lease_until <= #{now})
            """)
    int requeueExpiredIngestion(@Param("id") String id, @Param("now") long now);
}
