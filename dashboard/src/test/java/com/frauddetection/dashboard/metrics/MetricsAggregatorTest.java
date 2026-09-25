package com.frauddetection.dashboard.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.frauddetection.common.City;
import com.frauddetection.common.Decision;
import com.frauddetection.common.DecisionEvent;
import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.Transaction;
import com.frauddetection.dashboard.metrics.MetricsSnapshot.Point;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MetricsAggregatorTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00.500Z");
    private final MutableClock clock = new MutableClock(NOW);
    private final MetricsAggregator aggregator = new MetricsAggregator(clock);
    private final AtomicInteger ids = new AtomicInteger();

    private void record(Instant decidedAt, Decision decision, Double score, String rule, long latencyMs) {
        String id = "tx-" + ids.incrementAndGet();
        Transaction tx = new Transaction(id, "card-0001", 100_000, "GRAB", City.HA_NOI.location(), decidedAt);
        aggregator.record(new DecisionEvent(tx, new DecisionResult(id, decision, score, rule, null, latencyMs), decidedAt));
    }

    private Point lastPoint(MetricsSnapshot s) {
        return s.series().get(s.series().size() - 1);
    }

    @Test
    void countsDecisionsPerSecondAndOverTheHour() {
        Instant previousSecond = NOW.minusSeconds(1);
        for (int i = 0; i < 8; i++) {
            record(previousSecond, Decision.CHO_QUA, 0.01, null, 5);
        }
        record(previousSecond, Decision.XEM_XET, 0.6, null, 5);
        record(previousSecond, Decision.CHAN, null, "qua_nhieu_giao_dich", 5);

        MetricsSnapshot s = aggregator.snapshot();

        assertThat(s.series()).hasSize(MetricsAggregator.SERIES_SECONDS);
        assertThat(lastPoint(s).total()).isEqualTo(10);
        assertThat(lastPoint(s).chan()).isEqualTo(1);
        assertThat(lastPoint(s).xemXet()).isEqualTo(1);
        assertThat(s.lastHour().total()).isEqualTo(10);
        assertThat(s.lastHour().choQua()).isEqualTo(8);
        assertThat(s.currentTps()).isEqualTo(2.0); // 10 transactions over the last 5 seconds
    }

    @Test
    void latencyPercentilesComeFromTheHistogram() {
        Instant t = NOW.minusSeconds(2);
        for (int ms = 1; ms <= 100; ms++) {
            record(t, Decision.CHO_QUA, 0.0, null, ms);
        }

        MetricsSnapshot s = aggregator.snapshot();

        assertThat(s.lastMinute().count()).isEqualTo(100);
        assertThat(s.lastMinute().p50()).isEqualTo(50);
        assertThat(s.lastMinute().p99()).isEqualTo(99);
        assertThat(s.lastMinute().max()).isEqualTo(100);
    }

    @Test
    void eventsOlderThanOneHourAreIgnoredAndExpire() {
        record(NOW.minus(Duration.ofMinutes(61)), Decision.CHAN, null, "x", 5);
        record(NOW.minus(Duration.ofMinutes(59)), Decision.CHAN, null, "x", 5);
        assertThat(aggregator.snapshot().lastHour().total()).isEqualTo(1);

        clock.now = NOW.plus(Duration.ofMinutes(2));
        assertThat(aggregator.snapshot().lastHour().total()).isZero();
        assertThat(aggregator.snapshot().topRisky()).isEmpty();
    }

    @Test
    void topRiskyKeepsTheTenHighestAcrossMinutesWithRuleBlocksFirst() {
        for (int i = 0; i < 30; i++) {
            record(NOW.minusSeconds(60L * (i % 3) + 1), Decision.CHO_QUA, i / 100.0, null, 5);
        }
        record(NOW.minusSeconds(200), Decision.CHAN, null, "di_chuyen_bat_kha_thi", 5);
        record(NOW.minusSeconds(100), Decision.XEM_XET, 0.75, null, 5);

        var top = aggregator.snapshot().topRisky();

        assertThat(top).hasSize(10);
        assertThat(top.get(0).triggeredRule()).isEqualTo("di_chuyen_bat_kha_thi");
        assertThat(top.get(0).risk()).isEqualTo(1.0);
        assertThat(top.get(1).riskScore()).isEqualTo(0.75);
        assertThat(top.get(2).riskScore()).isEqualTo(0.29);
    }

    @Test
    void amongEqualRisksTheBiggerAmountRanksFirst() {
        Instant t = NOW.minusSeconds(5);
        Transaction small = new Transaction("small", "card-0001", 100_000, "GRAB", City.HA_NOI.location(), t);
        Transaction big = new Transaction("big", "card-0002", 90_000_000, "ATM", City.HA_NOI.location(), t);
        aggregator.record(new DecisionEvent(small, new DecisionResult("small", Decision.CHAN, null, "r", null, 5), t));
        aggregator.record(new DecisionEvent(big, new DecisionResult("big", Decision.CHAN, null, "r", null, 5), t));

        assertThat(aggregator.snapshot().topRisky()).extracting(MetricsSnapshot.RiskyTransaction::transactionId)
                .containsExactly("big", "small");
    }

    @Test
    void emptySecondsAppearAsZeroPoints() {
        MetricsSnapshot s = aggregator.snapshot();
        assertThat(lastPoint(s).total()).isZero();
        assertThat(lastPoint(s).p99()).isNull();
        assertThat(s.lastMinute().p99()).isNull();
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
