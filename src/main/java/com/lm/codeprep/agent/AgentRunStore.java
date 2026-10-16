package com.lm.codeprep.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.persistence.entity.AgentRunEntity;
import com.lm.codeprep.persistence.mapper.AgentRunMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static com.lm.codeprep.agent.AgentModels.RunResult;

@Service
public class AgentRunStore {
    private final Map<String, Entry> runs = new ConcurrentHashMap<>();
    private final AgentRunMapper mapper;
    private final ObjectMapper json;

    /** Isolated unit tests only; Spring injects the Mapper-backed constructor. */
    public AgentRunStore() { this.mapper = null; this.json = null; }

    @Autowired
    public AgentRunStore(AgentRunMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }

    public void save(String username, RunResult result) {
        if (mapper == null) { runs.put(result.runId(), new Entry(username, result)); return; }
        AgentRunEntity entity = new AgentRunEntity();
        entity.id = result.runId(); entity.username = username;
        try { entity.resultJson = json.writeValueAsString(result); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent 轨迹序列化失败", exception); }
        mapper.insert(entity);
    }
    public RunResult get(String username, String id) {
        if (mapper != null) {
            AgentRunEntity entity = mapper.selectById(id);
            if (entity == null) throw new IllegalArgumentException("Agent 运行记录不存在");
            if (!entity.username.equals(username)) throw new SecurityException("无权查看此 Agent 运行记录");
            try { return json.readValue(entity.resultJson, RunResult.class); }
            catch (JsonProcessingException exception) { throw new IllegalStateException("Agent 轨迹解析失败", exception); }
        }
        Entry entry = runs.get(id);
        if (entry == null) throw new IllegalArgumentException("Agent 运行记录不存在");
        if (!entry.username().equals(username)) throw new SecurityException("无权查看此 Agent 运行记录");
        return entry.result();
    }
    private record Entry(String username, RunResult result) { }
}
