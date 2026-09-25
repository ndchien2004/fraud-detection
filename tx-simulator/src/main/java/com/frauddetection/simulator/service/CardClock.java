package com.frauddetection.simulator.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
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
     * The card whose last transaction is the oldest (never-used cards first). Scenarios run on it,
     * so leftovers of a previous run (e.g. transactions still inside the 5-minute window) are unlikely.
     */
    public String leastRecentlyUsed(List<String> cardIds) {
        return cardIds.stream()
                .min(Comparator.comparing((String id) -> lastTimestamps.getOrDefault(id, Instant.MIN)))
                .orElseThrow();
    }
}
