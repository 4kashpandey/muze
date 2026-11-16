package com.muze.service.security;

import com.muze.domain.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

/**
 * Service for generating, parsing, and cryptographically validating JSON Web Tokens (JWT)
 * using HMAC-SHA256 digital signatures (JJWT 0.12.x).
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    // Default 256-bit secure secret key if not specified in environment
    private static final String DEFAULT_SECRET = "muze-ultra-secure-jwt-signing-secret-key-32bytes-long!";

    private final SecretKey signingKey;
    private final long tokenValidityHours;

    public JwtService(
            @Value("${muze.security.jwt.secret:" + DEFAULT_SECRET + "}") String secret,
            @Value("${muze.security.jwt.validity-hours:24}") long tokenValidityHours
    ) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            this.signingKey = Keys.hmacShaKeyFor(padded);
        } else {
            this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        }
        this.tokenValidityHours = tokenValidityHours;
    }

    /**
     * Generates a signed JWT token containing userId, username, email, and role.
     */
    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(tokenValidityHours, ChronoUnit.HOURS);

        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.getId() != null ? user.getId().toString() : "")
                .claim("role", user.getRole())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Extracts and validates claims from a signed JWT.
     * Returns empty if the token is expired, tampered with, or malformed.
     */
    public Optional<Claims> parseAndValidateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();

            if (claims.getExpiration() != null && claims.getExpiration().before(new Date())) {
                log.debug("JWT token is expired");
                return Optional.empty();
            }

            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> extractUsername(String token) {
        return parseAndValidateToken(token).map(Claims::getSubject);
    }

    public Optional<String> extractUserId(String token) {
        return parseAndValidateToken(token).map(claims -> claims.get("userId", String.class));
    }

    public Optional<String> extractRole(String token) {
        return parseAndValidateToken(token).map(claims -> claims.get("role", String.class));
    }
}