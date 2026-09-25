package com.frauddetection.dashboard.metrics;

import com.frauddetection.common.Decision;
import com.frauddetection.common.DecisionEvent;
import com.frauddetection.common.DecisionResult;
import com.frauddetection.dashboard.metrics.MetricsSnapshot.Latency;
import com.frauddetection.dashboard.metrics.MetricsSnapshot.Point;
import com.frauddetection.dashboard.metrics.MetricsSnapshot.RiskyTransaction;
import com.frauddetection.dashboard.metrics.MetricsSnapshot.Totals;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import org.springframework.stereotype.Component;

/**
 * Keeps the last hour of decisions in memory, as fixed-size "rings" instead of a growing list:
 * <ul>
 *   <li>3,600 one-second buckets: counts per decision + a latency histogram</li>
 *   <li>60 one-minute buckets: the 10 riskiest transactions of that minute</li>
 * </ul>
 * A bucket belongs to one second (or minute); when the ring comes back to it an hour later, it
 * is reset. So memory stays constant whether the system handles 1 or 500 transactions per second.
 *
 * <p>Buckets are keyed by the decision time carried in each event, so replaying the last hour
 * from Kafka at startup rebuilds exactly the same picture.
 */
@Component
public class MetricsAggregator {

    static final int WINDOW_SECONDS = 3600;
    static final int SERIES_SECONDS = 300;
    static final int TOP_N = 10;
    private static final int MINUTES = WINDOW_SECONDS / 60;
    /** Riskier first; among equal risks (e.g. many rule blocks at 1.0) the bigger amount, then the newest. */
    private static final Comparator<RiskyTransaction> BY_RISK = Comparator
            .comparingDouble(RiskyTransaction::risk)
            .thenComparingLong(RiskyTransaction::amount)
            .thenComparing(RiskyTransaction::decidedAt);

    private final Clock clock;
    private final SecondBucket[] seconds = new SecondBucket[WINDOW_SECONDS];
    private final MinuteBucket[] minutes = new MinuteBucket[MINUTES];

    public MetricsAggregator(Clock clock) {
        this.clock = clock;
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            seconds[i] = new SecondBucket();
        }
        for (int i = 0; i < MINUTES; i++) {
            minutes[i] = new MinuteBucket();
        }
    }

    public synchronized void record(DecisionEvent event) {
        DecisionResult result = event.result();
        Instant decidedAt = event.decidedAt();
        long nowSecond = clock.instant().getEpochSecond();
        long second = decidedAt.getEpochSecond();
        if (second <= nowSecond - WINDOW_SECONDS || second > nowSecond + 5) {
            return; // older than the window (or clock skew): ignore
        }

        SecondBucket s = seconds[(int) Math.floorMod(second, (long) WINDOW_SECONDS)];
        s.resetIfStale(second);
        s.total++;
        switch (result.decision()) {
            case CHAN -> s.chan++;
            case XEM_XET -> s.xemXet++;
            case CHO_QUA -> s.choQua++;
        }
        s.latency.record(result.latencyMs());

        long minute = Math.floorDiv(second, 60);
        MinuteBucket m = minutes[(int) Math.floorMod(minute, (long) MINUTES)];
        m.resetIfStale(minute);
        m.offer(risky(event));
    }

    public synchronized MetricsSnapshot snapshot() {
        Instant now = clock.instant();
        long nowSecond = now.getEpochSecond();
        // the current second is still filling up: charts end at the last complete second
        long lastComplete = nowSecond - 1;

        long total = 0;
        long choQua = 0;
        long xemXet = 0;
        long chan = 0;
        for (long sec = nowSecond - WINDOW_SECONDS + 1; sec <= nowSecond; sec++) {
            SecondBucket s = bucket(sec);
            if (s != null) {
                total += s.total;
                choQua += s.choQua;
                xemXet += s.xemXet;
                chan += s.chan;
            }
        }

        List<Point> series = new ArrayList<>(SERIES_SECONDS);
        for (long sec = lastComplete - SERIES_SECONDS + 1; sec <= lastComplete; sec++) {
            SecondBucket s = bucket(sec);
            series.add(s == null
                    ? new Point(sec, 0, 0, 0, null, null)
                    : new Point(sec, s.total, s.xemXet, s.chan, s.latency.percentile(50), s.latency.percentile(99)));
        }

        LatencyHistogram lastMinute = new LatencyHistogram();
        for (long sec = lastComplete - 59; sec <= lastComplete; sec++) {
            SecondBucket s = bucket(sec);
            if (s != null) {
                lastMinute.add(s.latency);
            }
        }

        long recent = 0;
        for (long sec = lastComplete - 4; sec <= lastComplete; sec++) {
            SecondBucket s = bucket(sec);
            recent += s == null ? 0 : s.total;
        }

        return new MetricsSnapshot(now, recent / 5.0,
                new Totals(total, choQua, xemXet, chan),
                new Latency(lastMinute.count(), lastMinute.percentile(50), lastMinute.percentile(95),
                        lastMinute.percentile(99), lastMinute.count() == 0 ? null : lastMinute.max()),
                series, topRisky(nowSecond));
    }

    private List<RiskyTransaction> topRisky(long nowSecond) {
        long nowMinute = Math.floorDiv(nowSecond, 60);
        long cutoff = nowSecond - WINDOW_SECONDS;
        List<RiskyTransaction> all = new ArrayList<>();
        for (MinuteBucket m : minutes) {
            if (m.minute > nowMinute - MINUTES) {
                m.top.stream().filter(r -> r.decidedAt().getEpochSecond() > cutoff).forEach(all::add);
            }
        }
        all.sort(BY_RISK.reversed());
        return List.copyOf(all.subList(0, Math.min(TOP_N, all.size())));
    }

    /** The bucket of that second, or null if the ring slot holds another (older) second. */
    private SecondBucket bucket(long second) {
        SecondBucket s = seconds[(int) Math.floorMod(second, (long) WINDOW_SECONDS)];
        return s.second == second ? s : null;
    }

    static RiskyTransaction risky(DecisionEvent event) {
        DecisionResult r = event.result();
        double risk = r.riskScore() != null ? r.riskScore()
                : r.decision() == Decision.CHAN ? 1.0
                : r.decision() == Decision.XEM_XET ? 0.6 : 0.0;
        return new RiskyTransaction(r.transactionId(), event.transaction().cardId(), event.transaction().amount(),
                event.transaction().merchant(), r.decision(), r.riskScore(), r.triggeredRule(), risk, event.decidedAt());
    }

    private static final class SecondBucket {
        long second = Long.MIN_VALUE;
        long total;
        long choQua;
        long xemXet;
        long chan;
        final LatencyHistogram latency = new LatencyHistogram();

        void resetIfStale(long newSecond) {
            if (second != newSecond) {
                second = newSecond;
                total = 0;
                choQua = 0;
                xemXet = 0;
                chan = 0;
                latency.clear();
            }
        }
    }

    /** The {@value #TOP_N} riskiest transactions of one minute (a min-heap: the least risky is evicted first). */
    private static final class MinuteBucket {
        long minute = Long.MIN_VALUE;
        final PriorityQueue<RiskyTransaction> top = new PriorityQueue<>(BY_RISK);

        void resetIfStale(long newMinute) {
            if (minute != newMinute) {
                minute = newMinute;
                top.clear();
            }
        }

        void offer(RiskyTransaction r) {
            if (top.size() < TOP_N) {
                top.add(r);
            } else if (BY_RISK.compare(r, top.peek()) > 0) {
                top.poll();
                top.add(r);
            }
        }
    }
}
