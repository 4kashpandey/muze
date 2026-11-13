package com.muze.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
        @Size(max = 60, message = "Room name cannot exceed 60 characters")
        String name,

        String userId,

        @NotBlank(message = "Username is required")
        @Size(min = 2, max = 30, message = "Username must be between 2 and 30 characters")
        String username,

        String initialTrackId,
        Boolean isPublic,
        String passcode
) {}