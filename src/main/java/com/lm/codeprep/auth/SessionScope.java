package com.lm.codeprep.auth;

import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class SessionScope {
    public String resolve(String username, String orgTag, String requested) {
        if (orgTag == null || orgTag.isBlank()) throw new IllegalArgumentException("组织标签不能为空");
        String prefix = username + ":" + orgTag + ":";
        if (requested == null || requested.isBlank()) return prefix + UUID.randomUUID();
        if (!requested.startsWith(prefix) || !requested.substring(prefix.length()).matches("[0-9a-fA-F-]{36}")) {
            throw new SecurityException("会话不属于当前用户或知识域");
        }
        return requested;
    }
}
