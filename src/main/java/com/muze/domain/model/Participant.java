package com.muze.domain.model;

import java.time.Instant;

/**
 * Immutable domain representation of a room participant.
 * Tracks guest status to prevent unauthenticated privilege escalation during leader election.
 */
public record Participant(
        String userId,
        String username,
        ParticipantRole role,
        Instant joinedAt,
        long lastHeartbeatMs,
        boolean isGuest
) {
    public Participant(String userId, String username, ParticipantRole role, Instant joinedAt, long lastHeartbeatMs) {
        this(userId, username, role, joinedAt, lastHeartbeatMs, username != null && username.startsWith("Guest-"));
    }

    public Participant withRole(ParticipantRole newRole) {
        return new Participant(userId, username, newRole, joinedAt, lastHeartbeatMs, isGuest);
    }

    public Participant withHeartbeat(long nowMs) {
        return new Participant(userId, username, role, joinedAt, nowMs, isGuest);
    }
}
