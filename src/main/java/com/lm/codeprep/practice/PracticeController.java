package com.lm.codeprep.practice;

import com.lm.codeprep.auth.OrgTagGuard;
import com.lm.codeprep.auth.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import static com.lm.codeprep.practice.PracticeModels.*;
/*这是一套**代码练习 / 刷题会话的 REST 接口**，和前面 RAG 知识库项目是同一个系统。

        - 基础路径前缀：`/api/practice`
        - 依赖：`PracticeAgentService` 练习业务服务；`OrgTagGuard` 组织权限校验组件（和前面文件上传接口里的 guard 是同一套权限组件）
        - 认证：基于 Spring Security；`Authentication`自动注入登录用户信息*/
@RestController
@RequestMapping("/api/practice")
public class PracticeController {
    private final PracticeAgentService practice;
    private final OrgTagGuard guard;

    public PracticeController(PracticeAgentService practice, OrgTagGuard guard) { this.practice = practice; this.guard = guard; }

    @PostMapping("/sessions")
    public Question start(@Valid @RequestBody StartRequest request, Authentication auth) {
        guard.checkRead(auth, request.orgTag()); return practice.start(((UserPrincipal) auth.getPrincipal()).getUsername(), request);
    }

    @PostMapping("/sessions/{sessionId}/answers")
    public AnswerResult answer(@PathVariable String sessionId, @Valid @RequestBody AnswerRequest request, Authentication auth) {
        return practice.answer(((UserPrincipal) auth.getPrincipal()).getUsername(), sessionId, request);
    }

    @GetMapping("/sessions/{sessionId}")
    public Session session(@PathVariable String sessionId, Authentication auth) {
        return practice.session(((UserPrincipal) auth.getPrincipal()).getUsername(), sessionId);
    }
}
