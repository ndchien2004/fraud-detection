package com.frauddetection.simulator.decision;

import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.Transaction;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls {@code POST /check-transaction} of decision-api. decision-api then records the
 * transaction in Kafka, so the simulator itself no longer talks to Kafka.
 */
@Component
public class DecisionApiClient {

    private final RestClient restClient;

    public DecisionApiClient(RestClient.Builder builder,
                             @Value("${app.decision-api.url}") String baseUrl,
                             @Value("${app.decision-api.connect-timeout-ms}") long connectTimeoutMs,
                             @Value("${app.decision-api.read-timeout-ms}") long readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public DecisionResult check(Transaction tx) {
        return restClient.post()
                .uri("/check-transaction")
                .contentType(MediaType.APPLICATION_JSON)
                .body(tx)
                .retrieve()
                .body(DecisionResult.class);
    }
}
