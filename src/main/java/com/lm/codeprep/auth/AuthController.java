package com.lm.codeprep.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AccountService accountService;
    private final JwtService jwtService;

    public AuthController(AccountService accountService, JwtService jwtService) {
        this.accountService = accountService;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        UserPrincipal principal = accountService.authenticate(request.username(), request.password());
        return new LoginResponse(jwtService.issue(principal), principal.getUsername(),
                principal.account().roles(), principal.account().organizationTags());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) { }
    public record LoginResponse(String token, String username, java.util.Set<String> roles, java.util.Set<String> organizationTags) { }
}
