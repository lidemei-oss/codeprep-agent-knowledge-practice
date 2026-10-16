package com.lm.codeprep.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("cp_chunk")
public class ChunkEntity {
    @TableId(value = "id", type = IdType.INPUT)
    public String id;
    public String documentId;
    public String title;
    public String content;
    public String category;
    public String orgTag;
    public Integer chunkNo;
}
