package com.muze.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * Service responsible for high-precision authoritative clock synchronization.
 * Supports Cristian's algorithm with multi-probe median filtering.
 */
@Service
public class ClockSyncService {

    private final Counter syncProbeCounter;

    public ClockSyncService(MeterRegistry meterRegistry) {
        this.syncProbeCounter = Counter.builder("muze.clock.sync.probes")
                .description("Total number of Cristian's algorithm clock synchronization probes processed")
                .register(meterRegistry);
    }

    /**
     * Returns current authoritative server epoch timestamp in milliseconds.
     */
    public long getCurrentServerEpochMs() {
        return System.currentTimeMillis();
    }

    /**
     * Records a clock probe invocation for observability metrics.
     */
    public void recordProbe() {
        syncProbeCounter.increment();
    }
}

