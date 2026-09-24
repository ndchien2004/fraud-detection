package com.frauddetection.simulator.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Spring's KafkaAdmin creates this topic on startup if it does not exist yet. */
@Configuration
class KafkaTopicConfig {

    @Bean
    NewTopic transactionsTopic(@Value("${app.topics.transactions}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }
}
