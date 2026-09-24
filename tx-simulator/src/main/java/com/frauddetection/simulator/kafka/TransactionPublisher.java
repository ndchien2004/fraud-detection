package com.frauddetection.simulator.kafka;

import com.frauddetection.common.Transaction;
import com.frauddetection.simulator.service.SimulatorStats;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Sends transactions to Kafka. The key is the cardId, so all transactions of one card land in the
 * same partition and keep their order.
 */
@Component
public class TransactionPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionPublisher.class);

    private final KafkaTemplate<String, Transaction> kafkaTemplate;
    private final SimulatorStats stats;
    private final String topic;

    public TransactionPublisher(KafkaTemplate<String, Transaction> kafkaTemplate,
                                SimulatorStats stats,
                                @Value("${app.topics.transactions}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.stats = stats;
        this.topic = topic;
    }

    public CompletableFuture<SendResult<String, Transaction>> publish(Transaction tx) {
        return kafkaTemplate.send(topic, tx.cardId(), tx).whenComplete((result, error) -> {
            if (error == null) {
                stats.recordSent();
            } else {
                stats.recordFailed();
                log.warn("Failed to publish {}: {}", tx.transactionId(), error.getMessage());
            }
        });
    }
}
