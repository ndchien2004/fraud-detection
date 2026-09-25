package com.frauddetection.feature.health;

import com.frauddetection.feature.history.HistorySeeder;
import org.apache.kafka.streams.KafkaStreams;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

/**
 * "UP" only when the service really does its job: the historical averages are in Redis and the
 * Kafka Streams topology is RUNNING. Without this, /actuator/health said UP as soon as the web
 * server started, ~10 s before the stream had its partitions, and Docker would route traffic
 * to a service that was not processing anything yet.
 */
@Component // shows up as "featureService" under /actuator/health
public class FeatureServiceHealthIndicator implements HealthIndicator {

    private final StreamsBuilderFactoryBean streams;
    private final HistorySeeder seeder;

    public FeatureServiceHealthIndicator(StreamsBuilderFactoryBean streams, HistorySeeder seeder) {
        this.streams = streams;
        this.seeder = seeder;
    }

    @Override
    public Health health() {
        KafkaStreams kafkaStreams = streams.getKafkaStreams();
        KafkaStreams.State state = kafkaStreams != null ? kafkaStreams.state() : null;
        Health.Builder builder = switch (state) {
            case RUNNING -> seeder.isSeeded() ? Health.up() : Health.outOfService();
            case CREATED, REBALANCING -> Health.outOfService(); // starting or rebalancing: temporarily not ready
            case null -> Health.outOfService();
            default -> Health.down(); // PENDING_ERROR, ERROR, PENDING_SHUTDOWN, NOT_RUNNING
        };
        return builder
                .withDetail("kafkaStreams", state == null ? "NOT_STARTED" : state.name())
                .withDetail("historicalAveragesSeeded", seeder.isSeeded())
                .build();
    }
}
