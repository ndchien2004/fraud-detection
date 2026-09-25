package com.frauddetection.simulator.service;

import com.frauddetection.common.Decision;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Counters shown next to the auto-mode switch. */
@Component
public class SimulatorStats {

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final Map<Decision, AtomicLong> byDecision = new EnumMap<>(Decision.class);

    public SimulatorStats() {
        for (Decision d : Decision.values()) {
            byDecision.put(d, new AtomicLong());
        }
    }

    public void recordDecision(Decision decision) {
        sent.incrementAndGet();
        byDecision.get(decision).incrementAndGet();
    }

    /** decision-api could not be reached. */
    public void recordFailed() {
        failed.incrementAndGet();
    }

    /** Auto mode skipped a transaction because all worker threads were busy. */
    public void recordDropped() {
        dropped.incrementAndGet();
    }

    public Snapshot snapshot() {
        return new Snapshot(sent.get(), byDecision.get(Decision.CHO_QUA).get(),
                byDecision.get(Decision.XEM_XET).get(), byDecision.get(Decision.CHAN).get(),
                failed.get(), dropped.get());
    }

    public record Snapshot(long totalSent, long choQua, long xemXet, long chan, long failed, long dropped) {
    }
}
