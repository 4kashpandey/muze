package com.muze.websocket.dto;

public enum MessageType {
    // Clock calibration (Cristian's algorithm)
    CLOCK_SYNC_REQUEST,
    CLOCK_SYNC_RESPONSE,

    // Playback control directives (Host -> Server)
    COMMAND_PLAY,
    COMMAND_PAUSE,
    COMMAND_SEEK,
    COMMAND_SELECT_TRACK,
    COMMAND_ENQUEUE_TRACK,
    COMMAND_SKIP_TRACK,
    COMMAND_REMOVE_QUEUED_TRACK,

    // State broadcasts (Server -> All Room Clients)
    SYNC_STATE,
    ROOM_PARTICIPANTS,
    ROOM_QUEUE_UPDATED,
    ROOM_CLOSED,

    // Diagnostics / System
    HEARTBEAT,
    ERROR
}

