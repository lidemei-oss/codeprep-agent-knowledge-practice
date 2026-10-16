package com.lm.codeprep.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lm.codeprep.persistence.entity.DocumentEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface DocumentMapper extends BaseMapper<DocumentEntity> {
    @Update("""
            UPDATE cp_document
            SET ingest_owner = #{token}, ingest_lease_until = #{leaseUntil}
            WHERE id = #{id} AND status = 'PROCESSING'
              AND (ingest_owner IS NULL OR ingest_lease_until <= #{now})
            """)
    int claimIngestion(@Param("id") String id, @Param("token") String token,
                       @Param("now") long now, @Param("leaseUntil") long leaseUntil);

    @Update("""
            UPDATE cp_document SET ingest_lease_until = #{leaseUntil}
            WHERE id = #{id} AND status = 'PROCESSING'
              AND ingest_owner = #{token} AND ingest_lease_until > #{now}
            """)
    int renewIngestion(@Param("id") String id, @Param("token") String token,
                       @Param("now") long now, @Param("leaseUntil") long leaseUntil);

    @Select("""
            SELECT COUNT(*) FROM cp_document
            WHERE id = #{id} AND status = 'PROCESSING'
              AND ingest_owner = #{token} AND ingest_lease_until > #{now}
            """)
    int ownsIngestion(@Param("id") String id, @Param("token") String token, @Param("now") long now);

    @Update("""
            UPDATE cp_document
            SET status = 'READY', chunk_count = #{chunkCount},
                ingest_owner = NULL, ingest_lease_until = NULL
            WHERE id = #{id} AND status = 'PROCESSING'
              AND ingest_owner = #{token} AND ingest_lease_until > #{now}
            """)
    int markReadyOwned(@Param("id") String id, @Param("token") String token,
                       @Param("chunkCount") int chunkCount, @Param("now") long now);

    @Update("""
            UPDATE cp_document
            SET status = 'FAILED', chunk_count = 0,
                ingest_owner = NULL, ingest_lease_until = NULL
            WHERE id = #{id} AND status = 'PROCESSING'
              AND ingest_owner = #{token} AND ingest_lease_until > #{now}
            """)
    int markFailedOwned(@Param("id") String id, @Param("token") String token, @Param("now") long now);

    @Update("""
            UPDATE cp_document SET status = 'FAILED', chunk_count = 0
            WHERE id = #{id} AND status = 'PROCESSING' AND ingest_owner IS NULL
            """)
    int markFailedUnclaimed(@Param("id") String id);
}
