package com.frauddetection.feature.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.CardState;
import com.frauddetection.common.Transaction;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.serializer.JsonSerde;

@Configuration
@EnableKafkaStreams
class KafkaStreamsConfig {

    /** Kafka Streams refuses to start if its source topic is missing, so make sure it exists. */
    @Bean
    NewTopic transactionsTopic(@Value("${app.topics.transactions}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    KStream<String, CardState> featurePipeline(StreamsBuilder builder,
                                               ObjectMapper objectMapper,
                                               CardStateSink sink,
                                               @Value("${app.topics.transactions}") String topic) {
        return FeatureTopology.build(builder, topic,
                jsonSerde(Transaction.class, objectMapper),
                jsonSerde(CardState.class, objectMapper),
                sink);
    }

    /** Plain JSON without Java type headers, matching what tx-simulator produces. */
    static <T> JsonSerde<T> jsonSerde(Class<T> type, ObjectMapper objectMapper) {
        return new JsonSerde<>(type, objectMapper).ignoreTypeHeaders().noTypeInfo();
    }
}
