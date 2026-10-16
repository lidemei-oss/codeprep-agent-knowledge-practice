package com.lm.codeprep.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("cp_document")
public class DocumentEntity {
    @TableId(value = "id", type = IdType.INPUT)
    public String id;
    public String title;
    public String category;
    public String orgTag;
    public String status;
    public String fileName;
    public Long createdAt;
    public Integer chunkCount;
    public String groupName;
}
