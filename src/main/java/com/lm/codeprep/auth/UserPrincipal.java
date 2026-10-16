package com.lm.codeprep.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

public final class UserPrincipal implements UserDetails {
    private final UserAccount account;
    private final Set<GrantedAuthority> authorities;

    public UserPrincipal(UserAccount account) {
        this.account = account;
        var values = new ArrayList<GrantedAuthority>();
        account.roles().forEach(role -> values.add(new SimpleGrantedAuthority("ROLE_" + role)));
        account.organizationTags().forEach(tag -> values.add(new SimpleGrantedAuthority("ORG_TAG:" + tag)));
        values.add(new SimpleGrantedAuthority("PERM_KNOWLEDGE_READ"));
        if (account.roles().stream().anyMatch(Set.of("OWNER", "EDITOR")::contains)) {
            values.add(new SimpleGrantedAuthority("PERM_KNOWLEDGE_WRITE"));
        }
        if (!account.roles().isEmpty()) values.add(new SimpleGrantedAuthority("PERM_PRACTICE_USE"));
        authorities = Set.copyOf(values);
    }

    public UserAccount account() { return account; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return account.passwordHash(); }
    @Override public String getUsername() { return account.username(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
