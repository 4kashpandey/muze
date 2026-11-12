package com.muze.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muze.domain.model.AnchorTimeline;
import com.muze.domain.model.Participant;
import com.muze.domain.model.QueuedTrack;
import com.muze.domain.model.Room;
import com.muze.domain.model.TrackMetadata;
import com.muze.service.ClockSyncService;
import com.muze.service.RoomService;
import com.muze.websocket.dto.MuzeMessage;
import com.muze.websocket.dto.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Optional;

@Component
public class MuzeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MuzeWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final RoomService roomService;
    private final ClockSyncService clockSyncService;
    private final SessionRegistry sessionRegistry;

    public MuzeWebSocketHandler(
            ObjectMapper objectMapper,
            RoomService roomService,
            ClockSyncService clockSyncService,
            SessionRegistry sessionRegistry
    ) {
        this.objectMapper = objectMapper;
        this.roomService = roomService;
        this.clockSyncService = clockSyncService;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String roomId = (String) session.getAttributes().get(MuzeHandshakeInterceptor.ATTR_ROOM_ID);
        String userId = (String) session.getAttributes().get(MuzeHandshakeInterceptor.ATTR_USER_ID);
        String username = (String) session.getAttributes().get(MuzeHandshakeInterceptor.ATTR_USERNAME);
        boolean isGuest = Boolean.TRUE.equals(session.getAttributes().get(MuzeHandshakeInterceptor.ATTR_IS_GUEST));

        try {
            roomService.joinRoom(roomId, userId, username, isGuest);
            sessionRegistry.register(session, roomId, userId, username);

            Room room = roomService.getRoomOrThrow(roomId);
            AnchorTimeline timeline = room.getTimeline();
            long now = clockSyncService.getCurrentServerEpochMs();

            // Send immediate canonical sync state to the new client
            MuzeMessage initialSync = MuzeMessage.syncState(
                    roomId,
                    room.getCurrentTrackId(),
                    room.getCurrentTrackUrl(),
                    room.getCurrentTrackTitle(),
                    room.getCurrentTrackArtist(),
                    room.getCurrentTrackArtworkUrl(),
                    room.getCurrentTrackDurationMs(),
                    timeline.status().name(),
                    timeline.anchorPositionMs(),
                    timeline.anchorServerTimeMs(),
                    timeline.playbackRate(),
                    timeline.epochSequence(),
                    now
            );
            sessionRegistry.sendToSession(session, initialSync);

            // Broadcast updated participant roster to everyone in the room
            broadcastRoster(roomId, room);
            // Broadcast active song queue
            broadcastQueue(roomId, room);

        } catch (Exception e) {
            log.error("Failed to establish session for user [{}] in room [{}]: {}", userId, roomId, e.getMessage());
            sessionRegistry.sendToSession(session, MuzeMessage.error("Join room failed: " + e.getMessage()));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        try {
            MuzeMessage msg = objectMapper.readValue(payload, MuzeMessage.class);
            if (msg.type() == null) {
                return;
            }

            SessionRegistry.SessionInfo info = sessionRegistry.getSessionInfo(session.getId()).orElse(null);
            if (info == null) {
                return;
            }

            String roomId = info.roomId();
            String userId = info.userId();

            switch (msg.type()) {
                case CLOCK_SYNC_REQUEST -> handleClockSync(session, msg);
                case COMMAND_PLAY -> handlePlay(roomId, userId);
                case COMMAND_PAUSE -> handlePause(roomId, userId);
                case COMMAND_SEEK -> handleSeek(roomId, userId, msg.seekPositionMs());
                case COMMAND_SELECT_TRACK -> handleSelectTrack(roomId, userId, msg);
                case COMMAND_ENQUEUE_TRACK -> handleEnqueueTrack(roomId, userId, info.username(), msg);
                case COMMAND_SKIP_TRACK -> handleSkipTrack(roomId, userId);
                case COMMAND_REMOVE_QUEUED_TRACK -> handleRemoveQueuedTrack(roomId, userId, msg.queueId());
                case HEARTBEAT -> roomService.getRoom(roomId).ifPresent(r -> r.recordHeartbeat(userId));
                default -> log.warn("Unhandled message type: {}", msg.type());
            }

        } catch (SecurityException e) {
            log.warn("Unauthorized command attempt from session {}: {}", session.getId(), e.getMessage());
            sessionRegistry.sendToSession(session, MuzeMessage.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Error processing WebSocket message from session {}: {}", session.getId(), e.getMessage(), e);
            sessionRegistry.sendToSession(session, MuzeMessage.error("Command error: " + e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Optional<SessionRegistry.SessionInfo> infoOpt = sessionRegistry.unregister(session.getId());
        if (infoOpt.isPresent()) {
            SessionRegistry.SessionInfo info = infoOpt.get();
            String roomId = info.roomId();
            String userId = info.userId();

            Optional<Participant> newHost = roomService.leaveRoom(roomId, userId);

            Optional<Room> remainingRoom = roomService.getRoom(roomId);
            if (remainingRoom.isPresent()) {
                broadcastRoster(roomId, remainingRoom.get());
                if (newHost.isPresent()) {
                    broadcastSyncState(roomId, remainingRoom.get());
                }
            } else {
                // Room has no authorized verified members remaining (or is empty).
                // Broadcast ROOM_CLOSED to all clients (including guests) and gracefully close sessions.
                log.info("Room [{}] ended with no authorized members remaining. Broadcasting ROOM_CLOSED.", roomId);
                sessionRegistry.broadcastToRoom(
                        roomId,
                        MuzeMessage.roomClosed("The host has left the room and no authorized members remain. Session ended.")
                );
                sessionRegistry.closeRoomSessions(roomId, "Room closed: no authorized members remain");
            }
        }
    }

    private void handleClockSync(WebSocketSession session, MuzeMessage request) {
        clockSyncService.recordProbe();
        long clientTime = request.clientTimestamp() != null ? request.clientTimestamp() : 0L;
        long serverTime = clockSyncService.getCurrentServerEpochMs();
        sessionRegistry.sendToSession(session, MuzeMessage.clockResponse(clientTime, serverTime));
    }

    private void handlePlay(String roomId, String userId) {
        roomService.executePlay(roomId, userId);
        Room room = roomService.getRoomOrThrow(roomId);
        broadcastSyncState(roomId, room);
    }

    private void handlePause(String roomId, String userId) {
        roomService.executePause(roomId, userId);
        Room room = roomService.getRoomOrThrow(roomId);
        broadcastSyncState(roomId, room);
    }

    private void handleSeek(String roomId, String userId, Long seekPositionMs) {
        long targetMs = seekPositionMs != null ? seekPositionMs : 0L;
        roomService.executeSeek(roomId, userId, targetMs);
        Room room = roomService.getRoomOrThrow(roomId);
        broadcastSyncState(roomId, room);
    }

    private void handleSelectTrack(String roomId, String userId, MuzeMessage msg) {
        if (msg.trackId() != null && !msg.trackId().isBlank()) {
            TrackMetadata track;
            if (msg.trackUrl() != null && !msg.trackUrl().isBlank()) {
                long duration = (msg.durationMs() != null && msg.durationMs() > 0) ? msg.durationMs() : 180000L;
                track = new TrackMetadata(
                        msg.trackId(),
                        msg.trackTitle() != null ? msg.trackTitle() : "Stream Track",
                        msg.trackArtist() != null ? msg.trackArtist() : "Artist",
                        duration,
                        null,
                        "Music",
                        msg.trackArtworkUrl(),
                        msg.trackUrl()
                );
            } else {
                track = roomService.resolveTrack(msg.trackId());
            }
            if (track != null) {
                roomService.executeSelectTrack(roomId, userId, track, true);
                Room room = roomService.getRoomOrThrow(roomId);
                broadcastSyncState(roomId, room);
            }
        }
    }

    private void broadcastSyncState(String roomId, Room room) {
        AnchorTimeline timeline = room.getTimeline();
        long now = clockSyncService.getCurrentServerEpochMs();

        MuzeMessage sync = MuzeMessage.syncState(
                roomId,
                room.getCurrentTrackId(),
                room.getCurrentTrackUrl(),
                room.getCurrentTrackTitle(),
                room.getCurrentTrackArtist(),
                room.getCurrentTrackArtworkUrl(),
                room.getCurrentTrackDurationMs(),
                timeline.status().name(),
                timeline.anchorPositionMs(),
                timeline.anchorServerTimeMs(),
                timeline.playbackRate(),
                timeline.epochSequence(),
                now
        );
        sessionRegistry.broadcastToRoom(roomId, sync);
    }

    private void broadcastRoster(String roomId, Room room) {
        List<MuzeMessage.ParticipantDto> dtos = room.getParticipants().stream()
                .map(p -> new MuzeMessage.ParticipantDto(p.userId(), p.username(), p.role().name()))
                .toList();

        sessionRegistry.broadcastToRoom(roomId, MuzeMessage.participants(roomId, dtos));
    }

    private void handleEnqueueTrack(String roomId, String userId, String username, MuzeMessage msg) {
        TrackMetadata track;
        if (msg.trackUrl() != null && !msg.trackUrl().isBlank()) {
            track = new TrackMetadata(
                    msg.trackId() != null ? msg.trackId() : "custom_" + System.currentTimeMillis(),
                    msg.trackTitle() != null ? msg.trackTitle() : "Streaming Track",
                    msg.trackArtist() != null ? msg.trackArtist() : "Audius Artist",
                    msg.durationMs() != null ? msg.durationMs() : 180000L,
                    null,
                    "Music",
                    msg.trackArtworkUrl(),
                    msg.trackUrl()
            );
        } else {
            track = roomService.resolveTrack(msg.trackId());
        }
        if (track != null) {
            roomService.enqueueTrack(roomId, userId, track, username);
            Room room = roomService.getRoomOrThrow(roomId);
            broadcastQueue(roomId, room);
        }
    }

    private void handleSkipTrack(String roomId, String userId) {
        Optional<QueuedTrack> next = roomService.skipTrack(roomId, userId);
        Room room = roomService.getRoomOrThrow(roomId);
        if (next.isPresent()) {
            broadcastSyncState(roomId, room);
        }
        broadcastQueue(roomId, room);
    }

    private void handleRemoveQueuedTrack(String roomId, String userId, String queueId) {
        roomService.removeQueuedTrack(roomId, userId, queueId);
        Room room = roomService.getRoomOrThrow(roomId);
        broadcastQueue(roomId, room);
    }

    private void broadcastQueue(String roomId, Room room) {
        List<MuzeMessage.QueuedTrackDto> dtos = room.getQueue().stream()
                .map(q -> new MuzeMessage.QueuedTrackDto(
                        q.queueId(),
                        q.trackId(),
                        q.streamUrl(),
                        q.title(),
                        q.artist(),
                        q.artworkUrl(),
                        q.durationMs(),
                        q.addedByUsername()
                ))
                .toList();
        sessionRegistry.broadcastToRoom(roomId, MuzeMessage.queueUpdated(roomId, dtos));
    }
}
