package com.lm.codeprep.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lm.codeprep.persistence.entity.ChunkEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChunkMapper extends BaseMapper<ChunkEntity> {
    @Select("""
            SELECT c.id, c.document_id, c.title, c.content, c.category, c.org_tag, c.chunk_no
            FROM cp_chunk c JOIN cp_document d ON d.id = c.document_id
            WHERE d.status = 'READY'
              AND (#{orgTag} IS NULL OR #{orgTag} = '' OR c.org_tag = #{orgTag})
              AND (#{category} IS NULL OR #{category} = '' OR c.category = #{category})
              AND (#{groupName} IS NULL OR d.group_name = #{groupName})
            ORDER BY d.created_at DESC, c.chunk_no
            """)
    List<ChunkEntity> selectReady(@Param("orgTag") String orgTag, @Param("category") String category,
                                  @Param("groupName") String groupName);
}
