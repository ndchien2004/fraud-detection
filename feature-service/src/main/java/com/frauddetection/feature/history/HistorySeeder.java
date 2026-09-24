package com.frauddetection.feature.history;

import com.frauddetection.common.CardProfile;
import com.frauddetection.common.CardProfiles;
import com.frauddetection.feature.redis.RedisCardStateRepository;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Feature {@code trung_binh_lich_su}: in a real bank a nightly batch job computes each card's
 * 30-day average from the transaction warehouse. Here we simulate that offline job at startup:
 * generate 30 days of history per simulated card and store the average in Redis.
 */
@Component
public class HistorySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HistorySeeder.class);
    static final int DAYS = 30;
    private static final int MAX_TRANSACTIONS_PER_DAY = 5;
    private static final double AMOUNT_SIGMA = 0.4;

    private final RedisCardStateRepository repository;

    public HistorySeeder(RedisCardStateRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (CardProfile card : CardProfiles.ALL) {
            repository.saveAverage(card.cardId(), historicalAverage(card));
        }
        log.info("Seeded 30-day historical averages for {} cards", CardProfiles.ALL.size());
    }

    /**
     * Average amount of 30 days of generated history. The random generator is seeded with the
     * card id, so every restart produces exactly the same history and average.
     */
    static double historicalAverage(CardProfile card) {
        Random rnd = new Random(card.cardId().hashCode());
        long total = 0;
        int count = 0;
        for (int day = 0; day < DAYS; day++) {
            int transactionsToday = 1 + rnd.nextInt(MAX_TRANSACTIONS_PER_DAY);
            for (int i = 0; i < transactionsToday; i++) {
                double factor = Math.exp(rnd.nextGaussian() * AMOUNT_SIGMA);
                total += Math.round(card.typicalAmount() * factor / 1000.0) * 1000;
                count++;
            }
        }
        return Math.round((double) total / count);
    }
}
