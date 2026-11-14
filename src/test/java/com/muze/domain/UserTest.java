package com.muze.domain;

import com.muze.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserTest {

    @Test
    @DisplayName("User entity initializes with valid fields and default role")
    void testUserInitialization() {
        User user = new User("sarah_connor", "sarah@skynet.com", "$2a$12$e8Yk...");

        assertEquals("sarah_connor", user.getUsername());
        assertEquals("sarah@skynet.com", user.getEmail());
        assertEquals("$2a$12$e8Yk...", user.getPasswordHash());
        assertEquals("ROLE_USER", user.getRole());
        assertNull(user.getId(), "ID should be null prior to persistence");
    }

    @Test
    @DisplayName("User equality is based on ID or username")
    void testUserEquality() {
        UUID id1 = UUID.randomUUID();
        User user1 = new User("john_doe", "john@example.com", "hash1");
        user1.setId(id1);

        User user2 = new User("john_doe", "john2@example.com", "hash2");
        user2.setId(id1);

        assertEquals(user1, user2);
        assertEquals(user1.hashCode(), user2.hashCode());
    }

    @Test
    @DisplayName("Custom role is respected during construction")
    void testCustomRole() {
        User host = new User("super_host", "host@muze.io", "hash", "ROLE_HOST");
        assertEquals("ROLE_HOST", host.getRole());
    }
}

