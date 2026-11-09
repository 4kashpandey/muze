package com.muze.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable catalog metadata for a streamable audio asset.
 * Supports both local media assets and decentralized Audius streams.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrackMetadata(
        String id,
        String title,
        String artist,
        long durationMs,
        String filename,
        String genre,
        String artworkUrl,
        String streamUrl
) {
    public TrackMetadata(String id, String title, String artist, long durationMs, String filename, String genre) {
        this(id, title, artist, durationMs, filename, genre, null, null);
    }

    public String effectiveStreamUrl() {
        if (streamUrl != null && !streamUrl.isBlank()) {
            return streamUrl;
        }
        return "/api/v1/tracks/" + id + "/stream";
    }
}
