package com.muze.domain.model;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe aggregate root representing an active synchronization room.
 * Encapsulates playback state mutations, participant presence, and leader election.
 */
public class Room {

    private final String roomId;
    private final String name;
    private final Instant createdAt;
    private final boolean isPublic;
    private final String passcode;
    private final String creatorUserId;
    private final Map<String, Participant> participants = new ConcurrentHashMap<>();
    private final List<QueuedTrack> trackQueue = new CopyOnWriteArrayList<>();
    private final AtomicReference<AnchorTimeline> timeline = new AtomicReference<>(AnchorTimeline.initial());

    private volatile String currentTrackId;
    private volatile String currentTrackUrl;
    private volatile String currentTrackTitle;
    private volatile String currentTrackArtist;
    private volatile String currentTrackArtworkUrl;
    private volatile long currentTrackDurationMs;
    private volatile String hostUserId;

    public Room(String roomId, String name, String creatorUserId, String creatorUsername, String initialTrackId) {
        this(roomId, name, creatorUserId, creatorUsername, initialTrackId, null, null, null, null, 0L, true, null);
    }

    public Room(
            String roomId,
            String name,
            String creatorUserId,
            String creatorUsername,
            String initialTrackId,
            String initialTrackUrl,
            String initialTrackTitle,
            String initialTrackArtist,
            String initialTrackArtworkUrl,
            long initialTrackDurationMs
    ) {
        this(roomId, name, creatorUserId, creatorUsername, initialTrackId, initialTrackUrl, initialTrackTitle, initialTrackArtist, initialTrackArtworkUrl, initialTrackDurationMs, true, null);
    }

    public Room(
            String roomId,
            String name,
            String creatorUserId,
            String creatorUsername,
            String initialTrackId,
            String initialTrackUrl,
            String initialTrackTitle,
            String initialTrackArtist,
            String initialTrackArtworkUrl,
            long initialTrackDurationMs,
            boolean isPublic,
            String passcode
    ) {
        this.roomId = Objects.requireNonNull(roomId, "roomId cannot be null");
        this.name = (name != null && !name.isBlank()) ? name : "Room " + roomId;
        this.createdAt = Instant.now();
        this.isPublic = isPublic;
        this.passcode = (passcode != null && !passcode.isBlank()) ? passcode.trim() : null;
        this.currentTrackId = (initialTrackId != null && !initialTrackId.isBlank()) ? initialTrackId : null;
        this.currentTrackUrl = (initialTrackUrl != null && !initialTrackUrl.isBlank())
                ? initialTrackUrl
                : (this.currentTrackId != null ? "/api/v1/tracks/" + this.currentTrackId + "/stream" : null);
        this.currentTrackTitle = (initialTrackTitle != null && !initialTrackTitle.isBlank()) ? initialTrackTitle : "No track playing";
        this.currentTrackArtist = (initialTrackArtist != null && !initialTrackArtist.isBlank()) ? initialTrackArtist : "Search or choose a track to begin";
        this.currentTrackArtworkUrl = initialTrackArtworkUrl;
        this.currentTrackDurationMs = initialTrackDurationMs;
        this.creatorUserId = creatorUserId;
        this.hostUserId = creatorUserId;

        // Add creator as initial HOST
        Participant host = new Participant(creatorUserId, creatorUsername, ParticipantRole.HOST, this.createdAt, System.currentTimeMillis());
        this.participants.put(creatorUserId, host);
    }

    public String getRoomId() {
        return roomId;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public boolean verifyPasscode(String code) {
        if (isPublic) {
            return true;
        }
        return passcode != null && passcode.equals(code != null ? code.trim() : "");
    }

    public String getCurrentTrackId() {
        return currentTrackId;
    }

    public String getCurrentTrackUrl() {
        if (currentTrackUrl != null && !currentTrackUrl.isBlank()) {
            return currentTrackUrl;
        }
        if (currentTrackId != null && !currentTrackId.isBlank()) {
            return "/api/v1/tracks/" + currentTrackId + "/stream";
        }
        return null;
    }

    public String getCurrentTrackTitle() {
        return currentTrackTitle;
    }

    public String getCurrentTrackArtist() {
        return currentTrackArtist;
    }

    public String getCurrentTrackArtworkUrl() {
        return currentTrackArtworkUrl;
    }

    public long getCurrentTrackDurationMs() {
        return currentTrackDurationMs;
    }

    public String getHostUserId() {
        return hostUserId;
    }

    public AnchorTimeline getTimeline() {
        return timeline.get();
    }

    public Collection<Participant> getParticipants() {
        return Collections.unmodifiableCollection(participants.values());
    }

    public Optional<Participant> getParticipant(String userId) {
        return Optional.ofNullable(participants.get(userId));
    }

    /**
     * Checks if a participant has authority to control playback.
     */
    public boolean hasPlaybackAuthority(String userId) {
        Participant p = participants.get(userId);
        return p != null && (p.role() == ParticipantRole.HOST || p.role() == ParticipantRole.CO_HOST);
    }

    /**
     * Adds or updates a participant joining the room.
     * Prevents duplicate instances of the same user by username/userId.
     * If the room already has an active HOST, any returning or new participant joins as a LISTENER only.
     */
    public synchronized Participant addParticipant(String userId, String username, boolean isGuest) {
        // Remove any stale participant with matching username or same userId to eliminate duplicate ghosts
        participants.entrySet().removeIf(e ->
                e.getKey().equals(userId) || (username != null && e.getValue().username().equalsIgnoreCase(username))
        );

        // Check if an active host is already present in the room
        boolean hasActiveHost = hostUserId != null && participants.containsKey(hostUserId)
                && participants.get(hostUserId).role() == ParticipantRole.HOST;

        ParticipantRole role;
        if (hasActiveHost) {
            // Room already has an active host (e.g. elected leader or existing host).
            // Any user re-joining or joining must be a LISTENER only.
            role = ParticipantRole.LISTENER;
        } else if (!isGuest) {
            // No active host and user is a verified member -> assign as HOST
            role = ParticipantRole.HOST;
            this.hostUserId = userId;
        } else {
            // Guest can never be host
            role = ParticipantRole.LISTENER;
        }

        Participant newParticipant = new Participant(userId, username, role, Instant.now(), System.currentTimeMillis(), isGuest);
        participants.put(userId, newParticipant);
        return newParticipant;
    }

    public synchronized Participant addParticipant(String userId, String username) {
        boolean guest = (username != null && username.startsWith("Guest-"));
        return addParticipant(userId, username, guest);
    }

    /**
     * Removes a participant. If the leaving participant was the HOST,
     * performs automatic leader election prioritizing verified members over guests.
     * Guests are never promoted to HOST. If no verified members remain, returns empty.
     * @return the new HOST participant if election occurred, or empty if room is empty or only guests remain.
     */
    public synchronized Optional<Participant> removeParticipant(String userId) {
        Participant removed = participants.remove(userId);
        if (removed == null) {
            return Optional.empty();
        }

        if (userId.equals(hostUserId)) {
            // Elect new host: earliest-joined VERIFIED member (skipping all guests!)
            Optional<Participant> nextLeader = participants.values().stream()
                    .filter(p -> !p.isGuest())
                    .min(Comparator.comparing(Participant::joinedAt));

            if (nextLeader.isPresent()) {
                Participant newHost = nextLeader.get().withRole(ParticipantRole.HOST);
                this.hostUserId = newHost.userId();
                participants.put(newHost.userId(), newHost);
                return Optional.of(newHost);
            } else {
                // No verified members remain; guests are never granted host authority
                this.hostUserId = null;
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Updates participant heartbeat.
     */
    public void recordHeartbeat(String userId) {
        participants.computeIfPresent(userId, (k, p) -> p.withHeartbeat(System.currentTimeMillis()));
    }

    // --- State Machine Mutations (Lock-Free Hardware CAS) ---

    public AnchorTimeline play(long serverTimeMs) {
        if (getCurrentTrackUrl() == null) {
            return timeline.get();
        }
        return timeline.updateAndGet(t -> t.transitionToPlay(serverTimeMs));
    }

    public AnchorTimeline pause(long serverTimeMs) {
        return timeline.updateAndGet(t -> t.transitionToPause(serverTimeMs));
    }

    public AnchorTimeline seek(long seekPositionMs, long serverTimeMs) {
        return timeline.updateAndGet(t -> t.transitionToSeek(seekPositionMs, serverTimeMs));
    }

    public AnchorTimeline selectTrack(TrackMetadata track, long serverTimeMs, boolean autoplay) {
        if (track == null) {
            return timeline.get();
        }
        this.currentTrackId = track.id();
        this.currentTrackUrl = track.effectiveStreamUrl();
        this.currentTrackTitle = track.title();
        this.currentTrackArtist = track.artist();
        this.currentTrackArtworkUrl = track.artworkUrl();
        this.currentTrackDurationMs = track.durationMs();
        return timeline.updateAndGet(t -> t.transitionToNewTrack(serverTimeMs, autoplay));
    }

    public AnchorTimeline selectTrack(String trackId, long serverTimeMs, boolean autoplay) {
        this.currentTrackId = trackId;
        this.currentTrackUrl = "/api/v1/tracks/" + trackId + "/stream";
        return timeline.updateAndGet(t -> t.transitionToNewTrack(serverTimeMs, autoplay));
    }

    // --- Song Queue Operations (FIFO) ---

    /**
     * Appends a track to the tail of the room's playback queue.
     */
    public synchronized QueuedTrack enqueueTrack(TrackMetadata track, String addedByUsername) {
        String queueId = "q_" + UUID.randomUUID().toString().substring(0, 8);
        QueuedTrack item = new QueuedTrack(
                queueId,
                track.id(),
                track.effectiveStreamUrl(),
                track.title(),
                track.artist(),
                track.artworkUrl(),
                track.durationMs(),
                (addedByUsername != null && !addedByUsername.isBlank()) ? addedByUsername : "Listener"
        );
        trackQueue.add(item);
        return item;
    }

    /**
     * Dequeues the next track from the head of the FIFO queue and transitions playback to it.
     * Preserves strict FIFO order for remaining items.
     */
    public synchronized Optional<QueuedTrack> dequeueNextTrack(long serverTimeMs) {
        if (trackQueue.isEmpty()) {
            return Optional.empty();
        }
        QueuedTrack next = trackQueue.remove(0);
        this.currentTrackId = next.trackId();
        this.currentTrackUrl = next.streamUrl();
        this.currentTrackTitle = next.title();
        this.currentTrackArtist = next.artist();
        this.currentTrackArtworkUrl = next.artworkUrl();
        this.currentTrackDurationMs = next.durationMs();

        this.timeline.updateAndGet(t -> t.transitionToNewTrack(serverTimeMs, true));
        return Optional.of(next);
    }

    /**
     * Deletes a specific track in between the queue by its queueId,
     * strictly preserving the relative order of all other queued tracks.
     */
    public synchronized boolean removeQueuedTrack(String queueId) {
        if (queueId == null) return false;
        return trackQueue.removeIf(item -> queueId.equals(item.queueId()));
    }

    public List<QueuedTrack> getQueue() {
        return Collections.unmodifiableList(trackQueue);
    }

    public int getQueueSize() {
        return trackQueue.size();
    }

    public boolean isEmpty() {
        return participants.isEmpty();
    }
}
