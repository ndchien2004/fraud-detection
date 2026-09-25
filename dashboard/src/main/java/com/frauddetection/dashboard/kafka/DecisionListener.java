package com.frauddetection.dashboard.kafka;

import com.frauddetection.common.DecisionEvent;
import com.frauddetection.dashboard.metrics.MetricsAggregator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

/**
 * Reads the {@code decisions} topic written by decision-api.
 *
 * <p>The dashboard keeps its numbers in memory only. So on every start (i.e. when Kafka assigns it
 * the partitions) it rewinds to "one hour ago" with {@code seekToTimestamp}: Kafka indexes messages
 * by time, and replaying that hour rebuilds the charts as if the dashboard had never been down.
 */
@Component
public class DecisionListener implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(DecisionListener.class);
    private static final Duration REPLAY = Duration.ofHours(1);

    private final MetricsAggregator aggregator;
    private final Clock clock;

    public DecisionListener(MetricsAggregator aggregator, Clock clock) {
        this.aggregator = aggregator;
        this.clock = clock;
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        long oneHourAgo = clock.millis() - REPLAY.toMillis();
        callback.seekToTimestamp(assignments.keySet(), oneHourAgo);
        log.info("Replaying the last hour of decisions on {}", assignments.keySet());
    }

    /** Batch listener: up to 2,000 events per call, which keeps up easily with 500 tx/s. */
    @KafkaListener(topics = "${app.topics.decisions}")
    public void onDecisions(List<DecisionEvent> events) {
        events.stream().filter(Objects::nonNull).forEach(aggregator::record);
    }
}
