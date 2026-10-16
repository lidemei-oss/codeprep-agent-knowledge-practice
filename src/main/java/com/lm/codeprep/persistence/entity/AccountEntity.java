package com.lm.codeprep.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("cp_account")
public class AccountEntity {
    @TableId(value = "username", type = IdType.INPUT)
    public String username;
    public String passwordHash;
    public String rolesJson;
    public String orgTagsJson;
}
