package com.frauddetection.simulator.service;

import com.frauddetection.simulator.kafka.TransactionPublisher;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Auto mode: publishes random transactions continuously at a configurable rate (1..500 per second). */
@Service
public class AutoModeService {

    private static final Logger log = LoggerFactory.getLogger(AutoModeService.class);
    public static final int DEFAULT_RATE = 10;

    private final TransactionGenerator generator;
    private final TransactionPublisher publisher;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "auto-mode"));

    private ScheduledFuture<?> task;
    private int ratePerSecond = DEFAULT_RATE;

    public AutoModeService(TransactionGenerator generator, TransactionPublisher publisher) {
        this.generator = generator;
        this.publisher = publisher;
    }

    public synchronized Status configure(boolean enabled, Integer newRate) {
        if (newRate != null) {
            ratePerSecond = newRate;
        }
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (enabled) {
            long periodMicros = 1_000_000L / ratePerSecond;
            task = scheduler.scheduleAtFixedRate(this::sendOne, 0, periodMicros, TimeUnit.MICROSECONDS);
            log.info("Auto mode ON at {} tx/s", ratePerSecond);
        } else {
            log.info("Auto mode OFF");
        }
        return status();
    }

    public synchronized Status status() {
        return new Status(task != null, ratePerSecond);
    }

    private void sendOne() {
        // an exception escaping a scheduled task would silently stop all future runs
        try {
            publisher.publish(generator.random());
        } catch (RuntimeException e) {
            log.warn("Auto mode could not send a transaction: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    public record Status(boolean enabled, int ratePerSecond) {
    }
}
