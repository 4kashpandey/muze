package com.muze.service.security;

import com.muze.domain.model.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;
    private User testUser;

    @BeforeEach
    void setUp() {
        // Use a test key of 32+ bytes and 24 hours validity
        jwtService = new JwtService("my-super-secret-test-key-must-be-at-least-32-bytes!", 24);
        testUser = new User("neil_peart", "neil@rush.com", "hash123", "ROLE_HOST");
        testUser.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("Should generate valid JWT and extract correct claims")
    void testGenerateAndValidateToken() {
        String token = jwtService.generateToken(testUser);
        assertNotNull(token);
        assertFalse(token.isBlank());

        Optional<Claims> claimsOpt = jwtService.parseAndValidateToken(token);
        assertTrue(claimsOpt.isPresent());

        Claims claims = claimsOpt.get();
        assertEquals("neil_peart", claims.getSubject());
        assertEquals(testUser.getId().toString(), claims.get("userId", String.class));
        assertEquals("ROLE_HOST", claims.get("role", String.class));
        assertEquals("neil@rush.com", claims.get("email", String.class));
    }

    @Test
    @DisplayName("Tampered token signature should fail validation")
    void testTamperedTokenFails() {
        String token = jwtService.generateToken(testUser);
        // Tamper with the last few characters of the cryptographic signature
        String tampered = token.substring(0, token.length() - 5) + "abcde";

        Optional<Claims> claimsOpt = jwtService.parseAndValidateToken(tampered);
        assertTrue(claimsOpt.isEmpty(), "Tampered signature should fail validation");
    }

    @Test
    @DisplayName("Blank or null token should return empty Optional")
    void testEmptyTokenFails() {
        assertTrue(jwtService.parseAndValidateToken(null).isEmpty());
        assertTrue(jwtService.parseAndValidateToken("   ").isEmpty());
    }
}