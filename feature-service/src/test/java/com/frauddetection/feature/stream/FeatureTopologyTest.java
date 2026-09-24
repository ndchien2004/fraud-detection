package com.frauddetection.feature.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.CardState;
import com.frauddetection.common.City;
import com.frauddetection.common.Transaction;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JsonSerde;

/** Runs the real topology in memory: no Kafka broker, no Redis. */
class FeatureTopologyTest {

    private static final String TOPIC = "transactions";
    private static final Instant T0 = Instant.parse("2026-08-19T10:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final JsonSerde<Transaction> txSerde = KafkaStreamsConfig.jsonSerde(Transaction.class, objectMapper);
    private final Map<String, CardState> redis = new HashMap<>();

    private TopologyTestDriver driver;
    private TestInputTopic<String, Transaction> input;

    @BeforeEach
    void setUp() {
        StreamsBuilder builder = new StreamsBuilder();
        FeatureTopology.build(builder, TOPIC, txSerde,
                KafkaStreamsConfig.jsonSerde(CardState.class, objectMapper), redis::put);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "feature-service-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        driver = new TopologyTestDriver(builder.build(), props);
        input = driver.createInputTopic(TOPIC, new StringSerializer(), txSerde.serializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    private void send(String cardId, Instant at, long amount) {
        input.pipeInput(cardId, new Transaction("tx", cardId, amount, "GRAB", City.HA_NOI.location(), at));
    }

    @Test
    void countsSixTransactionsWithinFiveMinutes() {
        for (int i = 0; i < 6; i++) {
            send("card-0001", T0.plusSeconds(i * 40L), 100_000);
        }

        CardState state = redis.get("card-0001");
        Instant last = T0.plusSeconds(200);
        assertThat(state.lastTimestamp()).isEqualTo(last);
        assertThat(state.countInWindow(last, Duration.ofMinutes(5))).isEqualTo(6);
        assertThat(state.sumInWindow(last, Duration.ofHours(1))).isEqualTo(600_000);
    }

    @Test
    void transactionsOlderThanOneHourLeaveTheTotal() {
        send("card-0001", T0, 1_000_000);
        send("card-0001", T0.plus(Duration.ofMinutes(90)), 200_000);

        CardState state = redis.get("card-0001");
        assertThat(state.sumInWindow(state.lastTimestamp(), Duration.ofHours(1))).isEqualTo(200_000);
    }

    @Test
    void keepsSeparateStatePerCard() {
        send("card-0001", T0, 100_000);
        send("card-0002", T0, 900_000);
        send("card-0001", T0.plusSeconds(20), 100_000);

        assertThat(redis.get("card-0001").sumInWindow(T0.plusSeconds(20), Duration.ofHours(1))).isEqualTo(200_000);
        assertThat(redis.get("card-0002").sumInWindow(T0, Duration.ofHours(1))).isEqualTo(900_000);

        KeyValueStore<String, CardState> store = driver.getKeyValueStore(FeatureTopology.STATE_STORE);
        assertThat(store.get("card-0001").buckets()).hasSize(2);
        assertThat(store.get("card-0002").buckets()).hasSize(1);
    }
}
