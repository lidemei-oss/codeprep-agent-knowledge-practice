package com.lm.codeprep.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("cp_agent_run")
public class AgentRunEntity {
    @TableId(value = "id", type = IdType.INPUT)
    public String id;
    public String username;
    public String resultJson;
}
