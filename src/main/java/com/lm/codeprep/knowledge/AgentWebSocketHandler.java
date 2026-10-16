package com.lm.codeprep.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lm.codeprep.auth.OrgTagGuard;
import com.lm.codeprep.auth.UserPrincipal;
import com.lm.codeprep.agent.AgentOrchestrator;
import com.lm.codeprep.agent.AgentModels.RunRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;

@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {
    private final KnowledgeAgentService agent; private final AgentOrchestrator orchestrator; private final ObjectMapper mapper; private final OrgTagGuard guard;
    public AgentWebSocketHandler(KnowledgeAgentService agent, AgentOrchestrator orchestrator, ObjectMapper mapper, OrgTagGuard guard) {
        this.agent = agent; this.orchestrator = orchestrator; this.mapper = mapper; this.guard = guard;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            var json = mapper.readTree(message.getPayload());
            String type = json.path("type").asText();
            if (!"KNOWLEDGE_ASK".equalsIgnoreCase(type) && !"AGENT_RUN".equalsIgnoreCase(type)) return;
            Authentication authentication = (Authentication) session.getAttributes().get("authentication");
            String orgTag = json.path("orgTag").asText("java"); guard.checkRead(authentication, orgTag);
            String username = ((UserPrincipal) authentication.getPrincipal()).getUsername();
            String question = json.path("question").asText();
            String category = json.path("category").asText("java"); String sessionId = json.path("sessionId").asText(null);
            if ("AGENT_RUN".equalsIgnoreCase(type)) {
                var request = new RunRequest(question, sessionId, category, orgTag, null, null, null, null,
                        json.path("allowWebSearch").asBoolean(true));
                var result = orchestrator.run(username, request);
                synchronized (session) { session.sendMessage(new TextMessage(mapper.writeValueAsString(result))); }
                return;
            }
            agent.stream(username, sessionId, question, category, orgTag,
                    json.path("allowWebSearch").asBoolean(true)).subscribe(event -> {
                try { if (session.isOpen()) synchronized (session) { session.sendMessage(new TextMessage(mapper.writeValueAsString(event))); } }
                catch (Exception exception) { try { session.close(); } catch (Exception ignored) { } }
            }, error -> sendError(session, error.getMessage()));
        } catch (Exception exception) { sendError(session, exception.getMessage() == null ? "消息格式错误" : exception.getMessage()); }
    }

    private void sendError(WebSocketSession session, String message) {
        try { if (session.isOpen()) session.sendMessage(new TextMessage(mapper.writeValueAsString(new KnowledgeModels.AgentEvent("error", message, List.of())))); }
        catch (Exception ignored) { }
    }
}
