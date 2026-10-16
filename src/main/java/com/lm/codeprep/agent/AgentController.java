package com.lm.codeprep.agent;

import com.lm.codeprep.auth.OrgTagGuard;
import com.lm.codeprep.auth.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.lm.codeprep.agent.AgentModels.*;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentOrchestrator agent;
    private final OrgTagGuard guard;
    public AgentController(AgentOrchestrator agent, OrgTagGuard guard) { this.agent = agent; this.guard = guard; }

    @PostMapping("/runs")
    public RunResult run(@Valid @RequestBody RunRequest request, Authentication authentication) {
        guard.checkRead(authentication, request.orgTag());
        return agent.run(((UserPrincipal) authentication.getPrincipal()).getUsername(), request);
    }

    @GetMapping("/runs/{runId}")
    public RunResult get(@PathVariable String runId, Authentication authentication) {
        return agent.get(((UserPrincipal) authentication.getPrincipal()).getUsername(), runId);
    }
}
