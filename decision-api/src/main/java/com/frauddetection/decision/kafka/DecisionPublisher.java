package com.frauddetection.decision.kafka;

import com.frauddetection.common.DecisionEvent;
import com.frauddetection.common.Transaction;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Records every checked transaction in Kafka, <b>after</b> it was scored:
 * <ul>
 *   <li>{@code transactions}: the feature service learns from it for the card's next transactions</li>
 *   <li>{@code decisions}: the dashboard reads it</li>
 * </ul>
 * Sends are asynchronous so Kafka never adds to the response time.
 */
@Component
public class DecisionPublisher {

    private static final Logger log = LoggerFactory.getLogger(DecisionPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String transactionsTopic;
    private final String decisionsTopic;

    public DecisionPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                             @Value("${app.topics.transactions}") String transactionsTopic,
                             @Value("${app.topics.decisions}") String decisionsTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.transactionsTopic = transactionsTopic;
        this.decisionsTopic = decisionsTopic;
    }

    public void publish(Transaction tx, DecisionEvent event) {
        send(transactionsTopic, tx.cardId(), tx);
        send(decisionsTopic, tx.cardId(), event);
    }

    private void send(String topic, String key, Object value) {
        try {
            kafkaTemplate.send(topic, key, value).whenComplete((result, error) -> {
                if (error != null) {
                    log.warn("Failed to publish to {}: {}", topic, error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            // e.g. Kafka down for longer than max.block.ms: the decision is still returned
            log.warn("Failed to publish to {}: {}", topic, e.getMessage());
        }
    }

    @Configuration
    static class Topics {

        @Bean
        NewTopic transactionsTopic(@Value("${app.topics.transactions}") String name) {
            return TopicBuilder.name(name).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic decisionsTopic(@Value("${app.topics.decisions}") String name) {
            return TopicBuilder.name(name).partitions(3).replicas(1).build();
        }
    }
}
