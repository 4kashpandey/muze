package com.muze.web.dto.auth;

import java.util.UUID;

public record AuthResponse(
        String token,
        UUID userId,
        String username,
        String email,
        String role
) {}

