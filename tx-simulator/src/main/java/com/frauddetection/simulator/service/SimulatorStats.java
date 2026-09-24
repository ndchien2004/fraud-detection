package com.frauddetection.simulator.service;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Counters shown next to the auto-mode switch. Decision counts are added in Phase 6. */
@Component
public class SimulatorStats {

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public void recordSent() {
        sent.incrementAndGet();
    }

    public void recordFailed() {
        failed.incrementAndGet();
    }

    public long sent() {
        return sent.get();
    }

    public long failed() {
        return failed.get();
    }
}
