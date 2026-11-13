package com.muze.web.dto;

import com.muze.domain.model.Participant;
import com.muze.domain.model.Room;

import java.util.List;

public record RoomResponse(
        String roomId,
        String name,
        String hostUserId,
        String currentTrackId,
        String status,
        long anchorPositionMs,
        long anchorServerTimeMs,
        long epochSequence,
        List<ParticipantDto> participants
) {
    public record ParticipantDto(
            String userId,
            String username,
            String role
    ) {}

    public static RoomResponse fromDomain(Room room) {
        var timeline = room.getTimeline();
        List<ParticipantDto> participantDtos = room.getParticipants().stream()
                .map(p -> new ParticipantDto(p.userId(), p.username(), p.role().name()))
                .toList();

        return new RoomResponse(
                room.getRoomId(),
                room.getName(),
                room.getHostUserId(),
                room.getCurrentTrackId(),
                timeline.status().name(),
                timeline.anchorPositionMs(),
                timeline.anchorServerTimeMs(),
                timeline.epochSequence(),
                participantDtos
        );
    }
}

