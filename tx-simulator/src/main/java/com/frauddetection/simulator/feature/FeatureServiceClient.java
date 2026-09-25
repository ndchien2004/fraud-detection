package com.frauddetection.simulator.feature;

import com.frauddetection.common.Location;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Asks feature-service what it knows about a card ({@code GET /features/{cardId}}): when and where
 * its last transaction happened. Scenarios use it to pick a card whose history cannot spoil the
 * demo, e.g. a card whose last payment was in TP.HCM 10 minutes ago would be blocked as
 * "impossible travel" on its very first transaction in Hà Nội.
 */
@Component
public class FeatureServiceClient {

    private final RestClient restClient;

    public FeatureServiceClient(RestClient.Builder builder, @Value("${app.feature-service.url}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(500))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(1));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public record CardActivity(Instant lastTimestamp, Location lastLocation) {
    }

    /** Empty when the card has no recent activity (feature-service answers 404). */
    public Optional<CardActivity> lastActivity(String cardId) {
        try {
            return Optional.ofNullable(restClient.get().uri("/features/{cardId}", cardId)
                    .retrieve()
                    .body(CardActivity.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
