package com.lm.codeprep.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class OrgTagGuard {
    public void checkRead(Authentication authentication, String tag) {
        check(authentication, tag, false);
    }

    public void checkWrite(Authentication authentication, String tag) {
        check(authentication, tag, true);
    }

    private void check(Authentication authentication, String tag, boolean write) {
        if (authentication == null || !authentication.isAuthenticated()) throw new SecurityException("需要登录");
        if (tag == null || tag.isBlank()) throw new IllegalArgumentException("组织标签不能为空");
        if (authentication.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_OWNER"))) return;
        boolean hasTag = authentication.getAuthorities().contains(new SimpleGrantedAuthority("ORG_TAG:" + tag.trim()));
        String permission = write ? "PERM_KNOWLEDGE_WRITE" : "PERM_KNOWLEDGE_READ";
        if (!hasTag || !authentication.getAuthorities().contains(new SimpleGrantedAuthority(permission))) {
            throw new SecurityException("当前组织标签没有访问该知识域的权限");
        }
    }
}
