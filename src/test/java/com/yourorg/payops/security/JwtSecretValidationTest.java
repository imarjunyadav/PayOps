package com.yourorg.payops.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Stage 4 JWT Secret Hardening Tests")
class JwtSecretValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(JwtService.class);

    private PayOpsUserDetails createDummyUser(String username, String role) {
        return new PayOpsUserDetails(
                1L,
                username,
                "hashedpassword",
                null,
                true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }

    @Test
    @DisplayName("Reject missing (null) secret with clear configuration error")
    void missingSecretThrowsConfigurationError() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtService(null, 3600000));
        assertTrue(ex.getMessage().contains("missing or blank"),
                "Expected message to mention missing or blank, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PAYOPS_JWT_SECRET"),
                "Expected message to mention PAYOPS_JWT_SECRET, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reject empty string secret with clear configuration error")
    void emptySecretThrowsConfigurationError() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtService("", 3600000));
        assertTrue(ex.getMessage().contains("missing or blank"),
                "Expected message to mention missing or blank, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PAYOPS_JWT_SECRET"),
                "Expected message to mention PAYOPS_JWT_SECRET, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reject whitespace-only secret with clear configuration error")
    void blankSecretThrowsConfigurationError() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtService("   \t  \n  ", 3600000));
        assertTrue(ex.getMessage().contains("missing or blank"),
                "Expected message to mention missing or blank, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PAYOPS_JWT_SECRET"),
                "Expected message to mention PAYOPS_JWT_SECRET, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reject short secret (12 bytes) without silently hashing into an acceptable key")
    void shortSecretThrowsWeakSecretError() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtService("short-secret", 3600000));
        assertTrue(ex.getMessage().contains("too weak"),
                "Expected message to mention too weak, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("32 bytes"),
                "Expected message to mention 32 bytes, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reject secret of exactly 31 bytes (one byte short of 256 bits)")
    void thirtyOneByteSecretThrowsWeakSecretError() {
        String thirtyOneBytes = "1234567890123456789012345678901"; // 31 chars
        assertEquals(31, thirtyOneBytes.length());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtService(thirtyOneBytes, 3600000));
        assertTrue(ex.getMessage().contains("too weak"),
                "Expected message to mention too weak, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("length 31 bytes"),
                "Expected message to state length 31 bytes, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reject short secret padded with spaces (cannot bypass via whitespace padding)")
    void paddedShortSecretThrowsWeakSecretError() {
        String padded = "   short-secret   ";
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtService(padded, 3600000));
        assertTrue(ex.getMessage().contains("too weak"),
                "Expected message to mention too weak, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid 32-byte secret successfully generates and parses tokens")
    void validExact32ByteSecretWorks() {
        String exact32Bytes = "12345678901234567890123456789012"; // exactly 32 bytes
        assertEquals(32, exact32Bytes.length());

        JwtService service = new JwtService(exact32Bytes, 3600000);
        PayOpsUserDetails user = createDummyUser("adminUser", "ADMIN");

        String token = service.generateToken(user);
        assertNotNull(token);

        Claims claims = service.parseAndValidateToken(token);
        assertEquals("adminUser", claims.getSubject());
        assertEquals(List.of("ADMIN"), service.extractRoles(token));
    }

    @Test
    @DisplayName("Valid strong secret (> 32 bytes) works correctly")
    void validStrongSecretWorks() {
        String strongKey = "payops-super-secure-production-random-secret-key-32-bytes-minimum!";
        JwtService service = new JwtService(strongKey, 3600000);
        PayOpsUserDetails user = createDummyUser("merchantUser", "MERCHANT");

        String token = service.generateToken(user);
        assertNotNull(token);

        Claims claims = service.parseAndValidateToken(token);
        assertEquals("merchantUser", claims.getSubject());
        assertEquals(List.of("MERCHANT"), service.extractRoles(token));
    }

    @Test
    @DisplayName("Preserve expiration behavior: expired token is rejected with ExpiredJwtException")
    void expiredTokenThrowsExpiredJwtException() {
        String strongKey = "payops-super-secure-production-random-secret-key-32-bytes-minimum!";
        // Negative expiration makes the token instantly expired
        JwtService expiredService = new JwtService(strongKey, -1000);
        PayOpsUserDetails user = createDummyUser("testUser", "ADMIN");

        String token = expiredService.generateToken(user);
        assertNotNull(token);

        assertThrows(ExpiredJwtException.class, () -> expiredService.parseAndValidateToken(token));
    }

    @Test
    @DisplayName("Preserve signature validation: tampered token is rejected with JwtException")
    void tamperedTokenSignatureIsRejected() {
        String strongKey = "payops-super-secure-production-random-secret-key-32-bytes-minimum!";
        JwtService service = new JwtService(strongKey, 3600000);
        PayOpsUserDetails user = createDummyUser("testUser", "ADMIN");

        String token = service.generateToken(user);
        String tamperedToken = token.substring(0, token.lastIndexOf('.') + 1) + "invalidSignatureHash";

        assertThrows(JwtException.class, () -> service.parseAndValidateToken(tamperedToken));
    }

    @Test
    @DisplayName("Application startup fails when payops.jwt.secret is blank or missing")
    void applicationStartupFailsWhenSecretMissingOrBlank() {
        contextRunner
                .withPropertyValues("payops.jwt.secret=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("missing or blank");
                });
    }

    @Test
    @DisplayName("Application startup fails when payops.jwt.secret is too weak")
    void applicationStartupFailsWhenSecretTooWeak() {
        contextRunner
                .withPropertyValues("payops.jwt.secret=too-short-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("too weak");
                });
    }

    @Test
    @DisplayName("Application startup succeeds when payops.jwt.secret is valid (>= 32 bytes)")
    void applicationStartupSucceedsWithValidSecret() {
        contextRunner
                .withPropertyValues("payops.jwt.secret=this-is-a-valid-secure-key-with-at-least-32-bytes-long!")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtService.class);
                });
    }
}
