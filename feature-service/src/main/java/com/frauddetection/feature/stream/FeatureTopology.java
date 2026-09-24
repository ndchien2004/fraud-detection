package com.frauddetection.feature.stream;

import com.frauddetection.common.CardState;
import com.frauddetection.common.Transaction;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * The Kafka Streams pipeline:
 * <pre>
 * transactions (key = cardId)
 *   -> groupByKey                  all transactions of one card together
 *   -> aggregate(CardState::add)   running state per card, kept in a local RocksDB store
 *   -> foreach(sink::write)        push every new state to Redis
 * </pre>
 * Kept free of Spring so tests can run it with {@code TopologyTestDriver}.
 */
public final class FeatureTopology {

    public static final String STATE_STORE = "card-state-store";

    private FeatureTopology() {
    }

    public static KStream<String, CardState> build(StreamsBuilder builder,
                                                   String transactionsTopic,
                                                   Serde<Transaction> transactionSerde,
                                                   Serde<CardState> stateSerde,
                                                   CardStateSink sink) {
        KStream<String, CardState> states = builder
                .stream(transactionsTopic, Consumed.with(Serdes.String(), transactionSerde)
                        .withTimestampExtractor(new TransactionTimestampExtractor()))
                .filter((cardId, tx) -> cardId != null && tx != null && tx.timestamp() != null)
                .groupByKey(Grouped.with(Serdes.String(), transactionSerde))
                .aggregate(
                        CardState::empty,
                        (cardId, tx, state) -> state.add(tx),
                        Materialized.<String, CardState, KeyValueStore<Bytes, byte[]>>as(STATE_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(stateSerde))
                .toStream();

        states.foreach(sink::write);
        return states;
    }
}
