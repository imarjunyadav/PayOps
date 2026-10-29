package com.yourorg.payops.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * Test helper that generates JWTs signed with a DIFFERENT key than the one configured
 * in application properties, so they fail signature validation in tests.
 */
public class TestJwtHelper {

    private static final String WRONG_KEY =
            "this-is-a-wrong-signing-key-and-must-be-at-least-256-bits-to-pass-jjwt!";

    public static String generateWithWrongKey(String username) {
        return Jwts.builder()
                .subject(username)
                .claim("roles", List.of("ADMIN"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(Keys.hmacShaKeyFor(WRONG_KEY.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
