package com.lm.codeprep.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.persistence.entity.AccountEntity;
import com.lm.codeprep.persistence.mapper.AccountMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AccountService implements UserDetailsService {
    private final PasswordEncoder passwordEncoder;
    private final AccountMapper mapper;
    private final ObjectMapper json;

    public AccountService(PasswordEncoder passwordEncoder, AccountMapper mapper, ObjectMapper json) {
        this.passwordEncoder = passwordEncoder;
        this.mapper = mapper; this.json = json;
        seed("alice", "alice123", Set.of("OWNER"), Set.of("java", "spring", "distributed"));
        seed("reader", "reader123", Set.of("READER", "PRACTICE"), Set.of("java"));
    }

    private void seed(String username, String password, Set<String> roles, Set<String> tags) {
        if (mapper.selectById(username) != null) return;
        AccountEntity entity = new AccountEntity();
        entity.username = username; entity.passwordHash = passwordEncoder.encode(password);
        try {
            entity.rolesJson = json.writeValueAsString(roles);
            entity.orgTagsJson = json.writeValueAsString(tags);
        } catch (JsonProcessingException exception) { throw new IllegalStateException("账号数据序列化失败", exception); }
        mapper.insert(entity);
    }

    public UserPrincipal authenticate(String username, String password) {
        UserAccount account = account(username);
        if (account == null || !passwordEncoder.matches(password, account.passwordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return new UserPrincipal(account);
    }

    public UserPrincipal load(String username) {
        UserAccount account = account(username);
        return account == null ? null : new UserPrincipal(account);
    }

    private UserAccount account(String username) {
        AccountEntity entity = mapper.selectById(username);
        if (entity == null) return null;
        try {
            return new UserAccount(entity.username, entity.passwordHash,
                    json.readValue(entity.rolesJson, new TypeReference<Set<String>>() { }),
                    json.readValue(entity.orgTagsJson, new TypeReference<Set<String>>() { }));
        } catch (JsonProcessingException exception) { throw new IllegalStateException("账号数据解析失败", exception); }
    }

    @Override
    public UserPrincipal loadUserByUsername(String username) {
        UserPrincipal principal = load(username);
        if (principal == null) throw new org.springframework.security.core.userdetails.UsernameNotFoundException(username);
        return principal;
    }
}
