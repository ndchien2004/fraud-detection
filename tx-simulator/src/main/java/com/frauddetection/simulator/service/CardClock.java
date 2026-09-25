package com.frauddetection.simulator.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-card "virtual clock". Scenarios may stamp a transaction in the future (e.g. "2 minutes later")
 * instead of really waiting; this clock guarantees that timestamps of one card never go backwards,
 * which the feature service relies on to find "the previous transaction".
 */
@Component
public class CardClock {

    private final Clock clock;
    private final Map<String, Instant> lastTimestamps = new ConcurrentHashMap<>();

    public CardClock(Clock clock) {
        this.clock = clock;
    }

    /** Timestamp for the next transaction of this card: now (or {@code requested}), but never before the last one. */
    public Instant next(String cardId, Instant requested) {
        Instant candidate = (requested != null ? requested : Instant.now(clock)).truncatedTo(ChronoUnit.MILLIS);
        return lastTimestamps.merge(cardId, candidate,
                (last, next) -> next.isAfter(last) ? next : last.plusMillis(1));
    }

    /**
     * Records a timestamp learnt from elsewhere (e.g. feature-service after a simulator restart),
     * so the next transaction of this card is stamped after it.
     */
    public void observe(String cardId, Instant timestamp) {
        lastTimestamps.merge(cardId, timestamp, (a, b) -> a.isAfter(b) ? a : b);
    }

    /** Last timestamp given to this card, or null if this simulator never used it. */
    public Instant last(String cardId) {
        return lastTimestamps.get(cardId);
    }
}
