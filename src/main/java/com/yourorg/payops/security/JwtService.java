package com.yourorg.payops.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for issuing and validating JSON Web Tokens (JWT) using JJWT.
 * <p>
 * Claims included:
 * <ul>
 *   <li>sub: username</li>
 *   <li>userId: database user ID</li>
 *   <li>roles: list of granted role names (e.g. ["ADMIN"] or ["MERCHANT"])</li>
 *   <li>merchantId: ID of associated merchant (null for internal users)</li>
 *   <li>iat / exp: timestamps</li>
 * </ul>
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${payops.jwt.secret:}") String secret,
            @Value("${payops.jwt.expiration-ms:86400000}") long expirationMs) {
        this.signingKey = initSigningKey(secret);
        this.expirationMs = expirationMs;
    }

    /**
     * Validates and initializes the HMAC-SHA256 signing key.
     * Requires at least 32 bytes (256 bits) of cryptographically secure secret material.
     * Does NOT silently hash shorter secrets into an acceptable key.
     */
    private static SecretKey initSigningKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT signing secret is missing or blank. Please configure PAYOPS_JWT_SECRET through the " +
                    "environment or secret-management mechanism with at least 32 bytes (256 bits) of cryptographically secure random material.");
        }
        String trimmed = secret.trim();
        byte[] raw = trimmed.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "JWT signing secret is too weak (length " + raw.length + " bytes). " +
                    "At least 32 bytes (256 bits) of cryptographically secure random material are required for HMAC-SHA256 signing.");
        }
        return Keys.hmacShaKeyFor(raw);
    }

    /**
     * Generates a signed JWT token for the authenticated user.
     */
    public String generateToken(PayOpsUserDetails userDetails) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.toList());

        var builder = Jwts.builder()
                .subject(userDetails.getUsername())
                .claim("userId", userDetails.getId())
                .claim("roles", roles)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey);

        if (userDetails.getMerchantId() != null) {
            builder.claim("merchantId", userDetails.getMerchantId());
        }

        return builder.compact();
    }

    /**
     * Parses and validates a JWT token.
     *
     * @param token the raw JWT token
     * @return the claims payload if valid
     * @throws JwtException if token signature is invalid, expired, malformed, or unsupported
     * @throws IllegalArgumentException if token is null or blank
     */
    public Claims parseAndValidateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("JWT token must not be null or blank");
        }
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return parseAndValidateToken(token).getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Claims claims = parseAndValidateToken(token);
        return claims.get("roles", List.class);
    }

    public Long extractMerchantId(String token) {
        Claims claims = parseAndValidateToken(token);
        Object mid = claims.get("merchantId");
        if (mid instanceof Number) {
            return ((Number) mid).longValue();
        }
        return null;
    }

    public Long extractUserId(String token) {
        Claims claims = parseAndValidateToken(token);
        Object uid = claims.get("userId");
        if (uid instanceof Number) {
            return ((Number) uid).longValue();
        }
        return null;
    }
}
