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
        TrackMetadata initialTrack = resolveTrack(initialTrackId);

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

    public Room getRoomOrThrow(String roomId) {
        return getRoom(roomId).orElseThrow(() -> new NoSuchElementException("Room not found: " + roomId));
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

    public TrackMetadata resolveTrack(String trackId) {
        if (trackId != null && !trackId.isBlank()) {
            return audiusService.getTrackById(trackId).orElse(null);
        }
        return null;
    }

    public Participant joinRoom(String roomId, String userId, String username, boolean isGuest) {
        Room room = getRoomOrThrow(roomId);
        Participant participant = room.addParticipant(userId, username, isGuest);
        log.info("User [{}] '{}' (guest={}) joined room [{}] with role [{}]",
                userId, username, isGuest, roomId, participant.role());
        return participant;
    }

    public Participant joinRoom(String roomId, String userId, String username) {
        boolean guest = (username != null && username.startsWith("Guest-"));
        return joinRoom(roomId, userId, username, guest);
    }

    public synchronized Optional<Participant> leaveRoom(String roomId, String userId) {
        Optional<Room> roomOpt = roomRepository.findById(roomId);
        if (roomOpt.isEmpty()) {
            return Optional.empty();
        }

        Room room = roomOpt.get();
        Optional<Participant> newHost = room.removeParticipant(userId);
        log.info("User [{}] left room [{}]", userId, roomId);

        // A room must have at least one authorized verified member to continue existing.
        boolean hasAuthorizedPerson = room.getParticipants().stream().anyMatch(p -> !p.isGuest());

        if (room.isEmpty() || !hasAuthorizedPerson) {
            roomRepository.deleteById(roomId);
            log.info("Room [{}] ended: either empty or contains no authorized verified members remaining. Pruned.", roomId);
            return Optional.empty();
        } else if (newHost.isPresent()) {
            log.info("Leader election in room [{}]: verified member [{}] '{}' is promoted to new HOST.",
                    roomId, newHost.get().userId(), newHost.get().username());
        }
        return newHost;
    }

    // --- Playback Control Directives ---

    public AnchorTimeline executePlay(String roomId, String userId) {
        Room room = getRoomOrThrow(roomId);
        validateAuthority(room, userId, "PLAY");
        if (room.getCurrentTrackUrl() == null) {
            log.warn("Room [{}] executePlay skipped: no track loaded", roomId);
            return room.getTimeline();
        }
        long now = clockSyncService.getCurrentServerEpochMs();
        AnchorTimeline updated = room.play(now);
        commandProcessedCounter.increment();
        log.info("Room [{}] PLAY at server time {} (epoch: {})", roomId, now, updated.epochSequence());
        return updated;
    }

    public AnchorTimeline executePause(String roomId, String userId) {
        Room room = getRoomOrThrow(roomId);
        validateAuthority(room, userId, "PAUSE");
        long now = clockSyncService.getCurrentServerEpochMs();
        AnchorTimeline updated = room.pause(now);
        commandProcessedCounter.increment();
        log.info("Room [{}] PAUSE at server time {} (pos: {} ms, epoch: {})", roomId, now, updated.anchorPositionMs(), updated.epochSequence());
        return updated;
    }

    public AnchorTimeline executeSeek(String roomId, String userId, long seekTargetMs) {
        Room room = getRoomOrThrow(roomId);
        validateAuthority(room, userId, "SEEK");
        long now = clockSyncService.getCurrentServerEpochMs();
        AnchorTimeline updated = room.seek(seekTargetMs, now);
        commandProcessedCounter.increment();
        log.info("Room [{}] SEEK to {} ms at server time {} (epoch: {})", roomId, seekTargetMs, now, updated.epochSequence());
        return updated;
    }

    public AnchorTimeline executeSelectTrack(String roomId, String userId, TrackMetadata track, boolean autoplay) {
        Room room = getRoomOrThrow(roomId);
        validateAuthority(room, userId, "SELECT_TRACK");
        long now = clockSyncService.getCurrentServerEpochMs();
        AnchorTimeline updated = room.selectTrack(track, now, autoplay);
        commandProcessedCounter.increment();
        log.info("Room [{}] selected track [{}] '{}' (autoplay: {}, epoch: {})",
                roomId, track.id(), track.title(), autoplay, updated.epochSequence());
        return updated;
    }

    public AnchorTimeline executeSelectTrack(String roomId, String userId, String trackId, boolean autoplay) {
        TrackMetadata track = resolveTrack(trackId);
        return executeSelectTrack(roomId, userId, track, autoplay);
    }

    // --- Song Queue Operations ---

    public QueuedTrack enqueueTrack(String roomId, String userId, TrackMetadata track, String username) {
        Room room = getRoomOrThrow(roomId);
        QueuedTrack item = room.enqueueTrack(track, username);
        commandProcessedCounter.increment();
        log.info("Room [{}] user [{}] queued track [{}] '{}' (queue size: {})",
                roomId, username, track.id(), track.title(), room.getQueueSize());
        return item;
    }

    public Optional<QueuedTrack> skipTrack(String roomId, String userId) {
        Room room = getRoomOrThrow(roomId);
        validateAuthority(room, userId, "SKIP");
        long now = clockSyncService.getCurrentServerEpochMs();
        Optional<QueuedTrack> next = room.dequeueNextTrack(now);
        commandProcessedCounter.increment();
        if (next.isPresent()) {
            log.info("Room [{}] skipped to next queued track [{}] '{}'", roomId, next.get().trackId(), next.get().title());
        } else {
            log.info("Room [{}] skip invoked but queue is empty", roomId);
        }
        return next;
    }

    public boolean removeQueuedTrack(String roomId, String userId, String queueId) {
        Room room = getRoomOrThrow(roomId);
        validateAuthority(room, userId, "REMOVE_QUEUED_TRACK");
        boolean removed = room.removeQueuedTrack(queueId);
        if (removed) {
            commandProcessedCounter.increment();
            log.info("Room [{}] removed queued item [{}] (remaining queue: {})", roomId, queueId, room.getQueueSize());
        }
        return removed;
    }

    private void validateAuthority(Room room, String userId, String action) {
        if (!room.hasPlaybackAuthority(userId)) {
            throw new SecurityException("User [" + userId + "] lacks authority to execute action '" + action + "' in room " + room.getRoomId());
        }
    }

    private String generateUniqueRoomId() {
        for (int attempts = 0; attempts < 100; attempts++) {
            StringBuilder sb = new StringBuilder(ROOM_ID_LENGTH);
            for (int i = 0; i < ROOM_ID_LENGTH; i++) {
                sb.append(ROOM_ID_CHARS.charAt(random.nextInt(ROOM_ID_CHARS.length())));
            }
            String candidate = sb.toString();
            if (!roomRepository.existsById(candidate)) {
                return candidate;
            }
        }
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}