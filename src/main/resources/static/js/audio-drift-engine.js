/**
 * Muze - Closed-Loop Audio Drift Engine
 * Continuously measures and aligns HTML5 audio playback against the authoritative timeline.
 * Features buffering/loading guard, hard-snap cooldown, and pitch-preserving micro-nudges.
 */
class AudioDriftEngine {
    constructor(clockSync) {
        this.clockSync = clockSync;
        this.audio = new Audio();
        this.audio.preload = 'auto';

        this.status = 'PAUSED';
        this.anchorPositionMs = 0;
        this.anchorServerTimeMs = 0;
        this.basePlaybackRate = 1.0;
        this.currentTrackUrl = '';
        this.expectedDurationSec = 180;
        this.isHost = false;

        this.driftCorrectionCount = 0;
        this.lastDeltaMs = 0;
        this.lastHardSnapTime = 0; // Cooldown timer for hard snaps
        this.syncState = 'IDLE'; // 'IN_SYNC', 'SOFT_NUDGE', 'HARD_SNAP'
        this.isUserInteracted = false;

        this.driftLoopInterval = null;
        this.listeners = [];

        this.setupAudioListeners();
    }

    setupAudioListeners() {
        this.audio.addEventListener('play', () => {
            this.isUserInteracted = true;
        });

        this.audio.addEventListener('error', (e) => {
            console.error('Audio stream element notice:', e);
        });
    }

    onTelemetryUpdate(callback) {
        this.listeners.push(callback);
    }

    setIsHost(isHost) {
        this.isHost = !!isHost;
        if (this.isHost) {
            this.audio.playbackRate = 1.0;
        }
    }

    applySyncState(msg) {
        const isNewTrack = (this.currentTrackUrl !== (msg.trackUrl || null));
        this.currentTrackUrl = msg.trackUrl || null;
        this.status = msg.status;
        this.anchorPositionMs = msg.anchorPositionMs || 0;
        this.anchorServerTimeMs = msg.anchorServerTimeMs || 0;
        this.basePlaybackRate = msg.playbackRate || 1.0;

        if (isNewTrack) {
            if (this.currentTrackUrl) {
                this.audio.src = this.currentTrackUrl;
                this.audio.load();
                this.lastHardSnapTime = Date.now();
            } else {
                this.audio.pause();
                this.audio.removeAttribute('src');
                this.audio.load();
            }
        }

        if (this.status === 'PLAYING' && this.currentTrackUrl) {
            const targetMs = this.calculateTargetPositionMs();
            const targetSeconds = Math.max(0, targetMs / 1000);

            // Align position once on initial state receive if significantly off (> 400ms)
            if (this.audio.paused || Math.abs((this.audio.currentTime * 1000) - targetMs) > 400) {
                this.audio.currentTime = targetSeconds;
                this.lastHardSnapTime = Date.now();
            }

            const playPromise = this.audio.play();
            if (playPromise !== undefined) {
                playPromise.catch(err => {
                    console.warn("Autoplay deferred until user interaction:", err.message);
                    this.notifyTelemetry(true);
                });
            }

            this.startDriftLoop();
        } else {
            // PAUSED / STOPPED or NO TRACK LOADED
            this.audio.pause();
            this.audio.playbackRate = 1.0;
            const targetSeconds = Math.max(0, this.anchorPositionMs / 1000);
            if (this.currentTrackUrl && !isNaN(this.audio.duration) && this.audio.duration > 0 && Math.abs(this.audio.currentTime - targetSeconds) > 0.05) {
                this.audio.currentTime = targetSeconds;
            }
            this.stopDriftLoop();
            this.syncState = 'PAUSED';
            this.notifyTelemetry(false);
        }
    }

    calculateTargetPositionMs() {
        if (this.status !== 'PLAYING') {
            return this.anchorPositionMs;
        }
        const nowServer = this.clockSync.getVirtualServerTimeMs();
        const elapsed = Math.max(0, nowServer - this.anchorServerTimeMs);
        return this.anchorPositionMs + Math.round(elapsed * this.basePlaybackRate);
    }

    startDriftLoop() {
        if (this.driftLoopInterval) return;

        // Run closed-loop alignment cycle every 300ms
        this.driftLoopInterval = setInterval(() => {
            this.evaluateDrift();
        }, 300);
    }

    stopDriftLoop() {
        if (this.driftLoopInterval) {
            clearInterval(this.driftLoopInterval);
            this.driftLoopInterval = null;
        }
    }

    evaluateDrift() {
        if (this.status !== 'PLAYING' || this.audio.paused || !this.currentTrackUrl) {
            return;
        }

        // Host is the master reference: never snap or nudge host's audio
        if (this.isHost) {
            this.syncState = 'IN_SYNC';
            if (this.audio.playbackRate !== 1.0) {
                this.audio.playbackRate = 1.0;
            }
            this.notifyTelemetry(false);
            return;
        }

        // Guard: If audio is currently seeking or still buffering chunks from network, DO NOT seek
        if (this.audio.seeking || this.audio.readyState < 3) {
            return;
        }

        const targetMs = this.calculateTargetPositionMs();
        const currentMs = this.audio.currentTime * 1000;
        const delta = Math.round(currentMs - targetMs);
        this.lastDeltaMs = delta;

        const absDelta = Math.abs(delta);

        if (absDelta < 50) {
            // Zone 1: Sub-50ms -> Perceptually aligned, zero correction needed
            this.syncState = 'IN_SYNC';
            if (this.audio.playbackRate !== 1.0) {
                this.audio.playbackRate = 1.0;
            }
        } else if (absDelta <= 400) {
            // Zone 2: 50ms - 400ms -> Gentle pitch-preserving tempo nudge (smooth, NO audio chops)
            this.syncState = 'SOFT_NUDGE';
            if (delta < 0) {
                // Audio lagging -> gently speed up (1.03x)
                this.audio.playbackRate = 1.03;
            } else {
                // Audio leading -> gently slow down (0.97x)
                this.audio.playbackRate = 0.97;
            }
            this.driftCorrectionCount++;
        } else {
            // Zone 3: > 400ms -> True desync: Hard Snap seek with 3-second cooldown
            const now = Date.now();
            if (now - this.lastHardSnapTime > 3000) {
                this.syncState = 'HARD_SNAP';
                this.audio.currentTime = Math.max(0, targetMs / 1000);
                this.audio.playbackRate = 1.0;
                this.lastHardSnapTime = now;
                this.driftCorrectionCount++;
            }
        }

        this.notifyTelemetry(false);
    }

    notifyTelemetry(autoplayBlocked) {
        const dur = (this.audio.duration && !isNaN(this.audio.duration) && this.audio.duration > 0)
            ? this.audio.duration
            : (this.expectedDurationSec || 180);

        const payload = {
            deltaMs: this.lastDeltaMs,
            syncState: this.syncState,
            status: this.status,
            currentTimeSec: this.audio.currentTime,
            durationSec: dur,
            correctionCount: this.driftCorrectionCount,
            playbackRate: this.audio.playbackRate,
            autoplayBlocked: autoplayBlocked
        };
        this.listeners.forEach(fn => {
            try { fn(payload); } catch (e) { console.error(e); }
        });
    }

    seekLocal(targetSeconds) {
        this.audio.currentTime = targetSeconds;
        this.lastHardSnapTime = Date.now();
    }

    setVolume(val) {
        this.audio.volume = Math.max(0, Math.min(1, val));
    }
}

window.AudioDriftEngine = AudioDriftEngine;
