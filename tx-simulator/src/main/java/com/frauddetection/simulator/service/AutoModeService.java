package com.frauddetection.simulator.service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Auto mode: sends random transactions continuously at a configurable rate (1..500 per second).
 *
 * <p>A scheduler "ticks" at the target rate and hands each transaction to a pool of worker
 * threads, because one HTTP call takes a few milliseconds: a single thread could not reach
 * 500 tx/s. If every worker is busy and the small queue is full, the tick is dropped (and
 * counted) instead of piling up an ever-growing backlog.
 */
@Service
public class AutoModeService {

    private static final Logger log = LoggerFactory.getLogger(AutoModeService.class);
    public static final int DEFAULT_RATE = 10;
    private static final int QUEUE_CAPACITY = 1_000;

    private final TransactionGenerator generator;
    private final SimulationService simulation;
    private final SimulatorStats stats;
    private final double anomalyShare;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "auto-mode-ticker"));
    private final ThreadPoolExecutor workers;

    private ScheduledFuture<?> task;
    private int ratePerSecond = DEFAULT_RATE;

    public AutoModeService(TransactionGenerator generator, SimulationService simulation, SimulatorStats stats,
                           @Value("${app.auto-mode.threads}") int threads,
                           @Value("${app.auto-mode.anomaly-share}") double anomalyShare) {
        this.generator = generator;
        this.simulation = simulation;
        this.stats = stats;
        this.anomalyShare = anomalyShare;
        this.workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), (task, executor) -> stats.recordDropped());
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
            task = ticker.scheduleAtFixedRate(() -> workers.execute(this::sendOne), 0, periodMicros, TimeUnit.MICROSECONDS);
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
        try {
            simulation.submit(generator.random(anomalyShare));
        } catch (RuntimeException e) {
            // already counted as failed; keep the log quiet when decision-api is down
            log.debug("Auto mode transaction failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        ticker.shutdownNow();
        workers.shutdownNow();
    }

    public record Status(boolean enabled, int ratePerSecond) {
    }
}
