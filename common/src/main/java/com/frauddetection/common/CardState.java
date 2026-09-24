package com.frauddetection.common;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Recent activity of one card, maintained by the feature service and stored in Redis.
 *
 * <p>Instead of keeping every transaction, amounts are grouped into 10-second buckets
 * (at most 360 buckets for 1 hour). This keeps the state small even at hundreds of
 * transactions per second, at the cost of up to 10 s imprecision at the window edge.
 *
 * @param buckets       non-empty buckets of the last hour, oldest first
 * @param lastLocation  location of the most recent transaction (null when empty)
 * @param lastTimestamp event time of the most recent transaction (null when empty)
 */
public record CardState(
        List<Bucket> buckets,
        Location lastLocation,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastTimestamp) {

    public static final long BUCKET_MILLIS = 10_000;
    public static final Duration RETENTION = Duration.ofHours(1);

    /** Transactions whose timestamp falls in [startMillis, startMillis + BUCKET_MILLIS). */
    public record Bucket(long startMillis, int count, long sum) {
    }

    public static CardState empty() {
        return new CardState(List.of(), null, null);
    }

    /** Returns a new state including {@code tx}; buckets older than 1 hour are dropped. */
    public CardState add(Transaction tx) {
        long ts = tx.timestamp().toEpochMilli();
        long start = Math.floorDiv(ts, BUCKET_MILLIS) * BUCKET_MILLIS;

        List<Bucket> updated = new ArrayList<>(buckets.size() + 1);
        boolean merged = false;
        for (Bucket b : buckets) {
            if (b.startMillis() == start) {
                updated.add(new Bucket(start, b.count() + 1, b.sum() + tx.amount()));
                merged = true;
            } else {
                updated.add(b);
            }
        }
        if (!merged) {
            updated.add(new Bucket(start, 1, tx.amount()));
            updated.sort(Comparator.comparingLong(Bucket::startMillis));
        }

        // a late (out-of-order) transaction must not overwrite the "last" location
        boolean isLatest = lastTimestamp == null || !tx.timestamp().isBefore(lastTimestamp);
        Instant newest = isLatest ? tx.timestamp() : lastTimestamp;
        long cutoff = newest.toEpochMilli() - RETENTION.toMillis();
        updated.removeIf(b -> b.startMillis() + BUCKET_MILLIS <= cutoff);

        return new CardState(List.copyOf(updated), isLatest ? tx.location() : lastLocation, newest);
    }

    /** Number of transactions in the window (at - window, at]. */
    public int countInWindow(Instant at, Duration window) {
        return bucketsInWindow(at, window).mapToInt(Bucket::count).sum();
    }

    /** Total amount of transactions in the window (at - window, at]. */
    public long sumInWindow(Instant at, Duration window) {
        return bucketsInWindow(at, window).mapToLong(Bucket::sum).sum();
    }

    /** Buckets overlapping the window, so a bucket straddling the edge counts entirely. */
    private Stream<Bucket> bucketsInWindow(Instant at, Duration window) {
        long to = at.toEpochMilli();
        long from = to - window.toMillis();
        return buckets.stream().filter(b -> b.startMillis() + BUCKET_MILLIS > from && b.startMillis() <= to);
    }
}
