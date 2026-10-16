package com.lm.codeprep.auth;

import java.util.Set;

public record UserAccount(String username, String passwordHash, Set<String> roles, Set<String> organizationTags) { }
