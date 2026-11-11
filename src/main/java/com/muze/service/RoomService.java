package com.muze.service;

import com.muze.domain.model.*;
import com.muze.domain.repository.RoomRepository;
import com.muze.service.audius.AudiusService;
import com.muze.web.dto.ActiveRoomSummaryDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    private static final String ROOM_ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ROOM_ID_LENGTH = 6;
    private final SecureRandom random = new SecureRandom();

    private final RoomRepository roomRepository;
    private final AudiusService audiusService;
    private final ClockSyncService clockSyncService;
    private final Counter roomCreatedCounter;
    private final Counter commandProcessedCounter;

    public RoomService(
            RoomRepository roomRepository,
            AudiusService audiusService,
            ClockSyncService clockSyncService,
            MeterRegistry meterRegistry
    ) {
        this.roomRepository = roomRepository;
        this.audiusService = audiusService;
        this.clockSyncService = clockSyncService;

        this.roomCreatedCounter = Counter.builder("muze.rooms.created")
                .description("Total rooms created")
                .register(meterRegistry);

        this.commandProcessedCounter = Counter.builder("muze.commands.processed")
                .description("Total playback commands processed")
                .register(meterRegistry);
    }

    public Room createRoom(
            String name,
            String creatorUserId,
            String creatorUsername,
            String initialTrackId,
            boolean isPublic,
            String passcode
    ) {
        String roomId = generateUniqueRoomId();
        TrackMetadata initialTrack = resolveTrackMetadata(initialTrackId);

        String trackId = initialTrack != null ? initialTrack.id() : null;
        String trackUrl = initialTrack != null ? initialTrack.effectiveStreamUrl() : null;
        String trackTitle = initialTrack != null ? initialTrack.title() : "No track playing";
        String trackArtist = initialTrack != null ? initialTrack.artist() : "Search catalog to begin";
        String trackArtwork = initialTrack != null ? initialTrack.artworkUrl() : null;
        long trackDuration = initialTrack != null ? initialTrack.durationMs() : 0L;

        Room room = new Room(
                roomId,
                name,
                creatorUserId,
                creatorUsername,
                trackId,
                trackUrl,
                trackTitle,
                trackArtist,
                trackArtwork,
                trackDuration,
                isPublic,
                passcode
        );

        // Creator automatically joins as initial HOST
        boolean isGuest = (creatorUsername != null && creatorUsername.startsWith("Guest-"));
        room.addParticipant(creatorUserId, creatorUsername, isGuest);

        roomRepository.save(room);
        roomCreatedCounter.increment();
        log.info("Created new {} room [{}] '{}' by user [{}] ({})",
                isPublic ? "PUBLIC" : "PRIVATE", roomId, name, creatorUserId, creatorUsername);

        return room;
    }

    public Optional<Room> getRoom(String roomId) {
        return roomRepository.findById(roomId);
    }

    public List<ActiveRoomSummaryDto> getActiveRooms() {
        return roomRepository.findAll().stream()
                .map(room -> {
                    String hostUsername = room.getParticipant(room.getHostUserId())
                            .map(Participant::username)
                            .orElse("Host");
                    return new ActiveRoomSummaryDto(
                            room.getRoomId(),
                            room.getName(),
                            hostUsername,
                            room.getParticipants().size(),
                            room.isPublic(),
                            room.getCurrentTrackTitle(),
                            room.getCurrentTrackArtist(),
                            room.getCurrentTrackArtworkUrl()
                    );
                })
                .toList();
    }

    private TrackMetadata resolveTrackMetadata(String trackId) {
        if (trackId != null && !trackId.isBlank()) {
            return audiusService.getTrackById(trackId).orElse(null);
        }
        return null;
    }

    public synchronized void leaveRoom(String roomId, String userId) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return;
        }

        Room room = roomOpt.get();
        room.removeParticipant(userId);
        log.info("User [{}] left room [{}]. Remaining participants: {}", userId, roomId, room.getParticipants().size());

        // Check if any verified (non-guest) members remain
        boolean hasVerifiedMember = room.getParticipants().stream()
                .anyMatch(p -> !p.isGuest());

        // Auto-cleanup: destroy room if completely empty or no verified members remain
        if (room.isEmpty() || !hasVerifiedMember) {
            roomRepository.deleteById(roomId);
            log.info("Room [{}] terminated and cleaned up (no verified members remaining).", roomId);
        }
    }

    private String generateUniqueRoomId() {
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder(ROOM_ID_LENGTH);
            for (int j = 0; j < ROOM_ID_LENGTH; j++) {
                sb.append(ROOM_ID_CHARS.charAt(random.nextInt(ROOM_ID_CHARS.length())));
            }
            String candidate = sb.toString();
            if (!roomRepository.existsById(candidate)) {
                return candidate;
            }
        }
        return UUID.randomUUID().toString().substring(0, ROOM_ID_LENGTH).toUpperCase();
    }
}