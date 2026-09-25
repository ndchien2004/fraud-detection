package com.frauddetection.common;

import java.util.Random;

/**
 * The 30-day average amount of a simulated card ({@code trung_binh_lich_su}).
 *
 * <p>In a real bank a nightly batch job computes it from the transaction warehouse. Here 30 days
 * of history are generated around the card's typical amount, with a random generator seeded by
 * the card id: every service and every restart gets exactly the same value. The feature service
 * seeds it into Redis; the simulator uses it to build the "20x the average" scenario.
 */
public final class HistoricalAverage {

    static final int DAYS = 30;
    private static final int MAX_TRANSACTIONS_PER_DAY = 5;
    private static final double AMOUNT_SIGMA = 0.4;

    private HistoricalAverage() {
    }

    public static double of(CardProfile card) {
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
