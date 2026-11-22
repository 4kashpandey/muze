/**
 * InSync - Robust WebSocket Transport Client
 * Manages full-duplex session, heartbeat, auto-reconnect, and message routing.
 */
class MuzeWebSocketClient {
    constructor(roomId, userId, username, isGuest = false) {
        this.roomId = roomId;
        this.userId = userId;
        this.username = username;
        this.isGuest = isGuest;
        this.socket = null;
        this.heartbeatTimer = null;
        this.handlers = {
            onOpen: [],
            onClose: [],
            onSyncState: [],
            onClockResponse: [],
            onParticipants: [],
            onQueueUpdated: [],
            onRoomClosed: [],
            onError: []
        };
        this.shouldReconnect = true;
    }

    on(event, callback) {
        if (this.handlers[event]) {
            this.handlers[event].push(callback);
        }
    }

    connect() {
        const protocol = (window.location.protocol === 'https:') ? 'wss:' : 'ws:';
        const host = window.location.host;
        const url = `${protocol}//${host}/ws?roomId=${encodeURIComponent(this.roomId)}&userId=${encodeURIComponent(this.userId)}&username=${encodeURIComponent(this.username)}&isGuest=${encodeURIComponent(this.isGuest)}`;

        this.socket = new WebSocket(url);

        this.socket.onopen = () => {
            console.log('Muze WebSocket connected:', url);
            this.startHeartbeat();
            this.emit('onOpen');
        };

        this.socket.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
                this.routeMessage(msg);
            } catch (err) {
                console.error('Failed to parse WebSocket frame:', err, event.data);
            }
        };

        this.socket.onclose = (event) => {
            console.warn('Muze WebSocket disconnected:', event.code, event.reason);
            this.stopHeartbeat();
            this.emit('onClose', event);

            if (this.shouldReconnect) {
                setTimeout(() => {
                    console.log('Attempting WebSocket reconnect...');
                    this.connect();
                }, 2000);
            }
        };

        this.socket.onerror = (error) => {
            console.error('Muze WebSocket error:', error);
            this.emit('onError', error);
        };
    }

    routeMessage(msg) {
        switch (msg.type) {
            case 'CLOCK_SYNC_RESPONSE':
                this.emit('onClockResponse', msg);
                break;
            case 'SYNC_STATE':
                this.emit('onSyncState', msg);
                break;
            case 'ROOM_PARTICIPANTS':
                this.emit('onParticipants', msg.participants);
                break;
            case 'ROOM_QUEUE_UPDATED':
                this.emit('onQueueUpdated', msg.queue || []);
                break;
            case 'ROOM_CLOSED':
                this.shouldReconnect = false;
                this.emit('onRoomClosed', msg.errorMessage || 'Room session ended.');
                break;
            case 'ERROR':
                this.emit('onError', msg.errorMessage || 'Server error');
                break;
            default:
                console.log('Unhandled message frame:', msg);
        }
    }

    enqueueTrack(trackId, title, artist, artworkUrl, trackUrl, durationMs) {
        this.send({
            type: 'COMMAND_ENQUEUE_TRACK',
            trackId: trackId,
            trackTitle: title,
            trackArtist: artist,
            trackArtworkUrl: artworkUrl,
            trackUrl: trackUrl,
            durationMs: durationMs
        });
    }

    skipTrack() {
        this.send({
            type: 'COMMAND_SKIP_TRACK'
        });
    }

    removeQueuedTrack(queueId) {
        this.send({
            type: 'COMMAND_REMOVE_QUEUED_TRACK',
            queueId: queueId
        });
    }

    send(payload) {
        if (this.isOpen()) {
            this.socket.send(JSON.stringify(payload));
        } else {
            console.warn('Cannot send, socket is not open:', payload);
        }
    }

    isOpen() {
        return this.socket && this.socket.readyState === WebSocket.OPEN;
    }

    startHeartbeat() {
        this.stopHeartbeat();
        this.heartbeatTimer = setInterval(() => {
            if (this.isOpen()) {
                this.send({ type: 'HEARTBEAT' });
            }
        }, 10000);
    }

    stopHeartbeat() {
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer);
            this.heartbeatTimer = null;
        }
    }

    disconnect() {
        this.shouldReconnect = false;
        this.stopHeartbeat();
        if (this.socket) {
            this.socket.close();
        }
    }

    emit(event, data) {
        if (this.handlers[event]) {
            this.handlers[event].forEach(fn => {
                try { fn(data); } catch (e) { console.error(e); }
            });
        }
    }
}

window.MuzeWebSocketClient = MuzeWebSocketClient;
window.InSyncWebSocketClient = MuzeWebSocketClient; // backwards compatibility alias

