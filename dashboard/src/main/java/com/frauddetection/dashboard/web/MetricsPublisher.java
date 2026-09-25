package com.frauddetection.dashboard.web;

import com.frauddetection.dashboard.metrics.MetricsAggregator;
import com.frauddetection.dashboard.metrics.MetricsSnapshot;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Pushes a fresh snapshot to every open dashboard each second; also serves it over HTTP. */
@RestController
public class MetricsPublisher {

    public static final String TOPIC = "/topic/metrics";

    private final MetricsAggregator aggregator;
    private final SimpMessagingTemplate messaging;

    public MetricsPublisher(MetricsAggregator aggregator, SimpMessagingTemplate messaging) {
        this.aggregator = aggregator;
        this.messaging = messaging;
    }

    @Scheduled(fixedRateString = "${app.push-interval-ms}")
    void push() {
        messaging.convertAndSend(TOPIC, aggregator.snapshot());
    }

    /** Same data as the WebSocket push: used for the first paint and handy from curl. */
    @GetMapping("/api/metrics")
    public MetricsSnapshot metrics() {
        return aggregator.snapshot();
    }
}
