package com.muze.websocket.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MuzeMessage(
        MessageType type,
        String roomId,
        String userId,
        Long clientTimestamp,
        Long serverTimestamp,
        Long serverEpochTime,
        Long seekPositionMs,
        String trackId,
        String trackUrl,
        String trackTitle,
        String trackArtist,
        String trackArtworkUrl,
        Long durationMs,
        String status,
        Long anchorPositionMs,
        Long anchorServerTimeMs,
        Double playbackRate,
        Long epochSequence,
        List<ParticipantDto> participants,
        String errorMessage,
        String queueId,
        List<QueuedTrackDto> queue
) {
    public record ParticipantDto(
            String userId,
            String username,
            String role
    ) {}

    public record QueuedTrackDto(
            String queueId,
            String trackId,
            String streamUrl,
            String title,
            String artist,
            String artworkUrl,
            long durationMs,
            String addedByUsername
    ) {}

    public static MuzeMessage clockResponse(long clientTimestamp, long serverTimestamp) {
        return new MuzeMessage(
                MessageType.CLOCK_SYNC_RESPONSE,
                null, null,
                clientTimestamp,
                serverTimestamp,
                serverTimestamp,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
    }

    public static MuzeMessage syncState(
            String roomId,
            String trackId,
            String trackUrl,
            String trackTitle,
            String trackArtist,
            String trackArtworkUrl,
            Long durationMs,
            String status,
            long anchorPositionMs,
            long anchorServerTimeMs,
            double playbackRate,
            long epochSequence,
            long serverEpochTime
    ) {
        return new MuzeMessage(
                MessageType.SYNC_STATE,
                roomId, null, null, null,
                serverEpochTime,
                null,
                trackId,
                trackUrl,
                trackTitle,
                trackArtist,
                trackArtworkUrl,
                durationMs,
                status,
                anchorPositionMs,
                anchorServerTimeMs,
                playbackRate,
                epochSequence,
                null, null, null, null
        );
    }

    public static MuzeMessage syncState(
            String roomId,
            String trackId,
            String trackUrl,
            String status,
            long anchorPositionMs,
            long anchorServerTimeMs,
            double playbackRate,
            long epochSequence,
            long serverEpochTime
    ) {
        return syncState(roomId, trackId, trackUrl, null, null, null, null, status, anchorPositionMs, anchorServerTimeMs, playbackRate, epochSequence, serverEpochTime);
    }

    public static MuzeMessage participants(String roomId, List<ParticipantDto> participants) {
        return new MuzeMessage(
                MessageType.ROOM_PARTICIPANTS,
                roomId, null, null, null,
                System.currentTimeMillis(),
                null, null, null, null, null, null, null, null, null, null, null, null,
                participants,
                null, null, null
        );
    }

    public static MuzeMessage queueUpdated(String roomId, List<QueuedTrackDto> queue) {
        return new MuzeMessage(
                MessageType.ROOM_QUEUE_UPDATED,
                roomId, null, null, null,
                System.currentTimeMillis(),
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null,
                queue
        );
    }

    public static MuzeMessage roomClosed(String message) {
        return new MuzeMessage(
                MessageType.ROOM_CLOSED,
                null, null, null, null,
                System.currentTimeMillis(),
                null, null, null, null, null, null, null, null, null, null, null, null,
                null,
                message,
                null, null
        );
    }

    public static MuzeMessage error(String message) {
        return new MuzeMessage(
                MessageType.ERROR,
                null, null, null, null,
                System.currentTimeMillis(),
                null, null, null, null, null, null, null, null, null, null, null, null,
                null,
                message,
                null, null
        );
    }
}
