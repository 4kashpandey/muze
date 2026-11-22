/**
 * Muze - Cristian's Clock Synchronization with Windowed Jitter Rejection
 * Calculates authoritative server time offset (theta) across variable network latency.
 */
class ClockSyncManager {
    constructor() {
        this.clockOffsetMs = 0; // theta
        this.lastRttMs = 0;
        this.isCalibrated = false;
        this.probeSamples = [];
        this.listeners = [];
        this.wsClient = null;
        this.calibrationInterval = null;
    }

    setWebSocketClient(wsClient) {
        this.wsClient = wsClient;
    }

    onCalibrationUpdate(callback) {
        this.listeners.push(callback);
    }

    /**
     * Dispatches a burst of 5 probes spaced 100ms apart.
     * Selects the lowest-RTT probe to reject outlier network jitter.
     */
    runCalibration(probeCount = 5) {
        if (!this.wsClient || !this.wsClient.isOpen()) {
            return;
        }

        this.probeSamples = [];

        for (let i = 0; i < probeCount; i++) {
            setTimeout(() => {
                if (this.wsClient && this.wsClient.isOpen()) {
                    const t0 = Date.now();
                    this.wsClient.send({
                        type: 'CLOCK_SYNC_REQUEST',
                        clientTimestamp: t0
                    });
                }
            }, i * 100);
        }
    }

    handleSyncResponse(msg) {
        const t1 = Date.now();
        const t0 = msg.clientTimestamp;
        const tServer = msg.serverTimestamp;

        const rtt = Math.max(1, t1 - t0);
        // Estimated server epoch timestamp at client receive time t1:
        const tServerEstAtT1 = tServer + (rtt / 2.0);
        // Clock offset theta:
        const theta = tServerEstAtT1 - t1;

        this.probeSamples.push({ rtt, theta });

        if (this.probeSamples.length >= 3) {
            // Sort by RTT ascending — minimum RTT exhibits closest symmetric route
            const sorted = [...this.probeSamples].sort((a, b) => a.rtt - b.rtt);
            const best = sorted[0];

            this.lastRttMs = Math.round(best.rtt);
            this.clockOffsetMs = Math.round(best.theta);
            this.isCalibrated = true;

            this.notifyListeners();
        }
    }

    getVirtualServerTimeMs() {
        return Date.now() + this.clockOffsetMs;
    }

    startPeriodicSync(intervalMs = 30000) {
        if (this.calibrationInterval) {
            clearInterval(this.calibrationInterval);
        }
        // Run immediately, then repeat every 30s
        this.runCalibration();
        this.calibrationInterval = setInterval(() => this.runCalibration(), intervalMs);
    }

    stopPeriodicSync() {
        if (this.calibrationInterval) {
            clearInterval(this.calibrationInterval);
            this.calibrationInterval = null;
        }
    }

    notifyListeners() {
        const state = {
            offsetMs: this.clockOffsetMs,
            rttMs: this.lastRttMs,
            isCalibrated: this.isCalibrated
        };
        this.listeners.forEach(fn => {
            try { fn(state); } catch (e) { console.error(e); }
        });
    }
}

window.ClockSyncManager = ClockSyncManager;
