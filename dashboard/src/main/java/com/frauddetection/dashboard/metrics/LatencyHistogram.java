package com.frauddetection.dashboard.metrics;

import java.util.Arrays;

/**
 * Counts latencies in 1 ms buckets from 0 to 999 ms, plus one bucket for 1 s and more.
 *
 * <p>Percentiles (p50, p99) need the whole distribution, not just an average: a histogram keeps
 * it in a fixed 4 KB whatever the traffic, and histograms of several seconds can simply be added
 * together to get the percentiles of a longer period.
 */
final class LatencyHistogram {

    static final int MAX_MS = 1000;

    private final int[] counts = new int[MAX_MS + 1];
    private long total;
    private long max;

    void record(long latencyMs) {
        int bucket = (int) Math.max(0, Math.min(MAX_MS, latencyMs));
        counts[bucket]++;
        total++;
        max = Math.max(max, latencyMs);
    }

    void add(LatencyHistogram other) {
        for (int i = 0; i < counts.length; i++) {
            counts[i] += other.counts[i];
        }
        total += other.total;
        max = Math.max(max, other.max);
    }

    void clear() {
        Arrays.fill(counts, 0);
        total = 0;
        max = 0;
    }

    long count() {
        return total;
    }

    long max() {
        return max;
    }

    /** Smallest latency such that at least {@code p} percent of the calls were at most that fast; null when empty. */
    Long percentile(double p) {
        if (total == 0) {
            return null;
        }
        long rank = (long) Math.ceil(total * p / 100.0);
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= rank) {
                return (long) i;
            }
        }
        return (long) MAX_MS;
    }
}
