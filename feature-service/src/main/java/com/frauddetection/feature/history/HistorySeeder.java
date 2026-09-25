package com.frauddetection.feature.history;

import com.frauddetection.common.CardProfile;
import com.frauddetection.common.CardProfiles;
import com.frauddetection.common.HistoricalAverage;
import com.frauddetection.feature.redis.RedisCardStateRepository;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Feature {@code trung_binh_lich_su}: simulates the bank's offline job that computes each card's
 * 30-day average, for the demo, scenario and background cards, and stores it in Redis.
 */
@Component
public class HistorySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HistorySeeder.class);
    /** Cards written per Redis MSET command. */
    private static final int BATCH_SIZE = 5_000;

    private final RedisCardStateRepository repository;
    private volatile boolean seeded;

    public HistorySeeder(RedisCardStateRepository repository) {
        this.repository = repository;
    }

    /** True once every average is in Redis (reported by the health check). */
    public boolean isSeeded() {
        return seeded;
    }

    @Override
    public void run(ApplicationArguments args) {
        long start = System.currentTimeMillis();
        Map<String, Double> batch = new HashMap<>();
        for (CardProfile card : CardProfiles.DEMO) {
            batch.put(card.cardId(), HistoricalAverage.of(card));
        }
        for (int n = 1; n <= CardProfiles.SCENARIO_COUNT; n++) {
            CardProfile card = CardProfiles.scenario(n);
            batch.put(card.cardId(), HistoricalAverage.of(card));
        }
        for (int n = 1; n <= CardProfiles.BACKGROUND_COUNT; n++) {
            CardProfile card = CardProfiles.background(n);
            batch.put(card.cardId(), HistoricalAverage.of(card));
            if (batch.size() >= BATCH_SIZE) {
                repository.saveAverages(batch);
                batch.clear();
            }
        }
        repository.saveAverages(batch);
        seeded = true;
        log.info("Seeded 30-day historical averages for {} demo + {} scenario + {} background cards in {} ms",
                CardProfiles.DEMO.size(), CardProfiles.SCENARIO_COUNT, CardProfiles.BACKGROUND_COUNT,
                System.currentTimeMillis() - start);
    }
}
