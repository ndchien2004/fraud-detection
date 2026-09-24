package com.frauddetection.feature.stream;

import com.frauddetection.common.Transaction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Uses the transaction's own timestamp (event time) instead of the time Kafka received it.
 * Needed because scenarios stamp transactions in the future ("2 minutes later").
 */
public class TransactionTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (record.value() instanceof Transaction tx && tx.timestamp() != null) {
            return tx.timestamp().toEpochMilli();
        }
        return record.timestamp();
    }
}
