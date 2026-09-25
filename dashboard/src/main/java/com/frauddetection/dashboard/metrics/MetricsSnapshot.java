package com.frauddetection.dashboard.metrics;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.frauddetection.common.Decision;
import java.time.Instant;
import java.util.List;

/** Everything the dashboard page needs, sent once per second over WebSocket and via GET /api/metrics. */
public record MetricsSnapshot(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant generatedAt,
        double currentTps,
        Totals lastHour,
        Latency lastMinute,
        List<Point> series,
        List<RiskyTransaction> topRisky) {

    /** Decisions counted over the last hour. */
    public record Totals(long total, long choQua, long xemXet, long chan) {
    }

    /** Latency percentiles in ms, null when there was no traffic. */
    public record Latency(long count, Long p50, Long p95, Long p99, Long max) {
    }

    /** One second of the time series. */
    public record Point(long epochSecond, long total, long xemXet, long chan, Long p50, Long p99) {
    }

    /**
     * @param risk the ML score, or 1.0 for a CHAN / 0.6 for a XEM_XET decided by a rule (no score)
     */
    public record RiskyTransaction(
            String transactionId, String cardId, long amount, String merchant,
            Decision decision, Double riskScore, String triggeredRule, double risk,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant decidedAt) {
    }
}
