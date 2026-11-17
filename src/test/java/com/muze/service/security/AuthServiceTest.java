package com.muze.service.security;

import com.muze.domain.model.User;
import com.muze.domain.repository.UserRepository;
import com.muze.web.dto.auth.AuthResponse;
import com.muze.web.dto.auth.LoginRequest;
import com.muze.web.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder(4); // Low cost factor for blazing fast tests
        jwtService = mock(JwtService.class);
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    @Test
    @DisplayName("Should successfully register a new user and return token")
    void testRegisterSuccess() {
        RegisterRequest request = new RegisterRequest("alex_lifeson", "alex@rush.com", "guitarHero1");

        when(userRepository.existsByUsername("alex_lifeson")).thenReturn(false);
        when(userRepository.existsByEmail("alex@rush.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(jwtService.generateToken(any(User.class))).thenReturn("mock-jwt-token");

        AuthResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals("mock-jwt-token", response.token());
        assertEquals("alex_lifeson", response.username());
        assertEquals("alex@rush.com", response.email());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("Duplicate username should throw IllegalArgumentException")
    void testRegisterDuplicateUsername() {
        RegisterRequest request = new RegisterRequest("geddy_lee", "geddy@rush.com", "bassMaster1");
        when(userRepository.existsByUsername("geddy_lee")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> authService.register(request));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Valid login credentials should return JWT token")
    void testLoginSuccess() {
        String rawPassword = "secretPassword";
        String encodedHash = passwordEncoder.encode(rawPassword);
        User user = new User("geddy_lee", "geddy@rush.com", encodedHash);
        user.setId(UUID.randomUUID());

        when(userRepository.findByUsername("geddy_lee")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user)).thenReturn("valid-login-token");

        AuthResponse response = authService.login(new LoginRequest("geddy_lee", rawPassword));

        assertNotNull(response);
        assertEquals("valid-login-token", response.token());
        assertEquals("geddy_lee", response.username());
    }

    @Test
    @DisplayName("Invalid password should throw BadCredentialsException")
    void testLoginInvalidPassword() {
        String encodedHash = passwordEncoder.encode("correctPassword");
        User user = new User("geddy_lee", "geddy@rush.com", encodedHash);

        when(userRepository.findByUsername("geddy_lee")).thenReturn(Optional.of(user));

        assertThrows(BadCredentialsException.class, () ->
                authService.login(new LoginRequest("geddy_lee", "wrongPassword"))
        );
    }
}