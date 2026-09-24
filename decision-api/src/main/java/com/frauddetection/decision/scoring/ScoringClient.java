package com.frauddetection.decision.scoring;

import com.frauddetection.common.ScoreResult;
import com.frauddetection.common.Transaction;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP client for the scoring service, with short timeouts: a slow answer is worse than a fallback. */
@Component
public class ScoringClient {

    private final RestClient restClient;

    public ScoringClient(RestClient.Builder builder,
                         @Value("${app.scoring.url}") String baseUrl,
                         @Value("${app.scoring.connect-timeout-ms}") long connectTimeoutMs,
                         @Value("${app.scoring.read-timeout-ms}") long readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public ScoreResult score(Transaction tx) {
        return restClient.post()
                .uri("/score")
                .contentType(MediaType.APPLICATION_JSON)
                .body(tx)
                .retrieve()
                .body(ScoreResult.class);
    }
}
