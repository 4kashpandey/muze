package com.muze.web.dto;

public record ActiveRoomSummaryDto(
        String roomId,
        String name,
        String hostUsername,
        int participantCount,
        boolean isPublic,
        String currentTrackTitle,
        String currentTrackArtist,
        String currentTrackArtworkUrl
) {}

