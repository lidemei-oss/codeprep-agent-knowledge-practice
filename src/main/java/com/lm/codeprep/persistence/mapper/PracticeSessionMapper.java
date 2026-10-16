package com.lm.codeprep.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lm.codeprep.persistence.entity.PracticeSessionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PracticeSessionMapper extends BaseMapper<PracticeSessionEntity> {
    @Select("SELECT id, username, state_json FROM cp_practice_session WHERE id = #{id} FOR UPDATE")
    PracticeSessionEntity selectForUpdate(@Param("id") String id);
}
