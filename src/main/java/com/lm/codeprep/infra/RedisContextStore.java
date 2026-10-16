package com.lm.codeprep.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RedisContextStore {
    private final ObjectProvider<StringRedisTemplate> redis;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final Map<String, List<String>> local = new ConcurrentHashMap<>();

    public RedisContextStore(ObjectProvider<StringRedisTemplate> redis, ObjectMapper mapper,
                             @Value("${codeprep.redis.enabled:false}") boolean enabled) {
        this.redis = redis; this.mapper = mapper; this.enabled = enabled;
    }

    public void append(String sessionId, String role, String content) {
        String key = "codeprep:chat:" + sessionId;
        try {
            if (!enabled) throw new IllegalStateException("redis disabled");
            StringRedisTemplate template = redis.getIfAvailable();
            if (template != null) {
                List<String> messages = read(key);
                messages.add(role + ": " + content);
                template.opsForValue().set(key, mapper.writeValueAsString(messages), Duration.ofHours(6));
                return;
            }
        } catch (Exception ignored) { }
        local.computeIfAbsent(key, ignored -> new ArrayList<>()).add(role + ": " + content);
    }

    public List<String> read(String sessionId) {
        String key = sessionId.startsWith("codeprep:") ? sessionId : "codeprep:chat:" + sessionId;
        try {
            if (!enabled) throw new IllegalStateException("redis disabled");
            StringRedisTemplate template = redis.getIfAvailable();
            if (template != null) {
                String value = template.opsForValue().get(key);
                if (value != null) return mapper.readValue(value, new TypeReference<>() { });
            }
        } catch (Exception ignored) { }
        return new ArrayList<>(local.getOrDefault(key, List.of()));
    }

    public boolean acquireIdempotency(String key, Duration ttl) {
        try {
            if (!enabled) return false;
            StringRedisTemplate template = redis.getIfAvailable();
            if (template != null) return Boolean.TRUE.equals(template.opsForValue().setIfAbsent("codeprep:idempotency:" + key, "1", ttl));
        } catch (Exception ignored) { }
        return local.putIfAbsent("idempotency:" + key, List.of()) == null;
    }

    public boolean allow(String key, int limit, Duration window) {
        try {
            if (!enabled) return true;
            StringRedisTemplate template = redis.getIfAvailable();
            if (template != null) {
                String redisKey = "codeprep:rate:" + key;
                Long count = template.opsForValue().increment(redisKey);
                if (count != null && count == 1) template.expire(redisKey, window);
                return count == null || count <= limit;
            }
        } catch (Exception ignored) { }
        return true;
    }
}
