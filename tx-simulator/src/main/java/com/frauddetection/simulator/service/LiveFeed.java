package com.frauddetection.simulator.service;

import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.Transaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes every checked transaction to browsers over WebSocket (STOMP topic {@value #TOPIC}).
 *
 * <p>Events are buffered and sent as one batch every flush interval: at 500 tx/s, sending each
 * one separately would flood the browser. A batch carries at most {@value #MAX_BATCH} events
 * (the newest), since the page only shows the last 100 rows anyway.
 */
@Component
public class LiveFeed {

    public static final String TOPIC = "/topic/live-feed";
    static final int MAX_BATCH = 200;

    private final SimpMessagingTemplate messaging;
    private final Queue<Event> pending = new ConcurrentLinkedQueue<>();

    public LiveFeed(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public record Event(Transaction transaction, DecisionResult result) {
    }

    public void publish(Transaction tx, DecisionResult result) {
        pending.add(new Event(tx, result));
    }

    @Scheduled(fixedDelayString = "${app.live-feed.flush-interval-ms}")
    void flush() {
        List<Event> batch = new ArrayList<>();
        Event e;
        while ((e = pending.poll()) != null) {
            batch.add(e);
        }
        if (batch.isEmpty()) {
            return;
        }
        List<Event> newest = batch.size() > MAX_BATCH ? batch.subList(batch.size() - MAX_BATCH, batch.size()) : batch;
        messaging.convertAndSend(TOPIC, newest);
    }
}
