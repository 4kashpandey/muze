package com.muze.domain.model;

/**
 * Anchor-based temporal mathematics for low-latency synchronization.
 * 
 * Instead of continuous 100ms server polling (which causes network congestion and audio jitter),
 * the server maintains an immutable anchor snapshot. Clients compute their exact position locally:
 * 
 * Position(t) = anchorPositionMs + (t_server - anchorServerTimeMs) * playbackRate
 */
public record AnchorTimeline(
    long anchorPositionMs,
    long anchorServerTimeMs,
    double playbackRate,
    PlaybackStatus status,
    long epochSequence
) {
    public static AnchorTimeline initial() {

        return new AnchorTimeline(0L, 0L, 1.0, PlaybackStatus.PAUSED, 1L);
    }

    /**
     * Calculates the exact authoritative playback position at any given server timestamp.
     */
    public long calculateCurrentPosition(long currentServerTimeMs) {
        if (status != PlaybackStatus.PLAYING) {
            return anchorPositionMs;
        }
        long elapsedMs = Math.max(0, currentServerTimeMs - anchorServerTimeMs);
        return anchorPositionMs + Math.round(elapsedMs * playbackRate);
    }

    /**
     * State transition: PLAY command received at server time T_play.
     */
    public AnchorTimeline transitionToPlay(long serverTimeMs) {
        if (status == PlaybackStatus.PLAYING) {
            return this; // Idempotent
        }
        return new AnchorTimeline(
            this.anchorPositionMs,
            serverTimeMs,
            1.0,
            PlaybackStatus.PLAYING,
            this.epochSequence + 1
        );
    }

    /**
     * State transition: PAUSE command received at server time T_pause.
     */
    public AnchorTimeline transitionToPause(long serverTimeMs) {
        if (status == PlaybackStatus.PAUSED) {
            return this; // Idempotent
        }
        long currentPos = calculateCurrentPosition(serverTimeMs);
        return new AnchorTimeline(
            currentPos,
            0L,
            1.0,
            PlaybackStatus.PAUSED,
            this.epochSequence + 1
        );
    }

    /**
     * State transition: SEEK command to seekTargetMs received at server time T_seek.
     */
    public AnchorTimeline transitionToSeek(long seekTargetMs, long serverTimeMs) {
        long sanitizedTarget = Math.max(0L, seekTargetMs);
        if (status == PlaybackStatus.PLAYING) {
            return new AnchorTimeline(
                sanitizedTarget,
                serverTimeMs,
                this.playbackRate,
                PlaybackStatus.PLAYING,
                this.epochSequence + 1
            );
        } else {
            return new AnchorTimeline(
                sanitizedTarget,
                0L,
                this.playbackRate,
                PlaybackStatus.PAUSED,
                this.epochSequence + 1
            );
        }
    }

    /**
     * State transition: Switch active track, resetting anchor position to 0.
     */
    public AnchorTimeline transitionToNewTrack(long serverTimeMs, boolean autoplay) {
        if (autoplay) {
            return new AnchorTimeline(0L, serverTimeMs, 1.0, PlaybackStatus.PLAYING, this.epochSequence + 1);
        } else {
            return new AnchorTimeline(0L, 0L, 1.0, PlaybackStatus.PAUSED, this.epochSequence + 1);
        }
    }
}

