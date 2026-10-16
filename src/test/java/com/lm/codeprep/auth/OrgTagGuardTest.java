package com.lm.codeprep.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OrgTagGuardTest {
    @Test
    void readerCanReadOwnTagButCannotReadOthersOrWrite() {
        var principal = new UserPrincipal(new UserAccount("reader", "irrelevant", Set.of("READER"), Set.of("java")));
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        var guard = new OrgTagGuard();
        assertDoesNotThrow(() -> guard.checkRead(auth, "java"));
        assertThrows(SecurityException.class, () -> guard.checkRead(auth, "finance"));
        assertThrows(SecurityException.class, () -> guard.checkWrite(auth, "java"));
        assertThrows(IllegalArgumentException.class, () -> guard.checkRead(auth, null));
    }
}
