package com.muze.domain.model;

/**
 * Immutable domain representation of a track queued in a room's FIFO playback queue.
 * Each queued item carries a unique queueId so that specific tracks can be deleted
 * or inspected while strictly preserving the relative FIFO order of the queue.
 */
public record QueuedTrack(
        String queueId,
        String trackId,
        String streamUrl,
        String title,
        String artist,
        String artworkUrl,
        long durationMs,
        String addedByUsername
) {}

