package com.frauddetection.simulator.service;

import com.frauddetection.common.CardProfile;
import com.frauddetection.common.CardProfiles;
import com.frauddetection.common.City;
import com.frauddetection.common.Location;
import com.frauddetection.common.Merchant;
import com.frauddetection.common.Transaction;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Builds transactions: random ones for auto mode, or exactly what the user / a scenario asked for. */
@Component
public class TransactionGenerator {

    /** ~0.03 degrees is about 3 km: transactions scatter around the city centre. */
    private static final double LOCATION_JITTER_DEGREES = 0.03;
    /** Spread of the log-normal amount distribution around the card's typical amount. */
    private static final double AMOUNT_SIGMA = 0.4;
    private static final long MIN_AMOUNT = 10_000;
    private static final City[] FOREIGN_CITIES = {City.SINGAPORE, City.BANGKOK, City.TOKYO};

    private final CardClock cardClock;
    private final AtomicLong sequence = new AtomicLong();

    public TransactionGenerator(CardClock cardClock) {
        this.cardClock = cardClock;
    }

    /**
     * Auto-mode transaction of a random background card. Most are ordinary purchases in the card's
     * home city around its typical amount; {@code anomalyShare} of them are deliberately odd
     * (half 10-30x the typical amount, half paid in a foreign city) so the dashboard has something to block.
     */
    public Transaction random(double anomalyShare) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        CardProfile card = CardProfiles.background(1 + rnd.nextInt(CardProfiles.BACKGROUND_COUNT));
        Merchant merchant = Merchant.values()[rnd.nextInt(Merchant.values().length)];

        double factor = Math.exp(rnd.nextGaussian() * AMOUNT_SIGMA);
        City city = card.homeCity();
        double roll = rnd.nextDouble();
        if (roll < anomalyShare / 2) {
            factor = rnd.nextDouble(10, 30);
        } else if (roll < anomalyShare) {
            city = FOREIGN_CITIES[rnd.nextInt(FOREIGN_CITIES.length)];
        }
        long amount = Math.max(MIN_AMOUNT, roundToThousand(card.typicalAmount() * factor));

        Location center = city.location();
        Location location = new Location(
                center.lat() + rnd.nextDouble(-LOCATION_JITTER_DEGREES, LOCATION_JITTER_DEGREES),
                center.lon() + rnd.nextDouble(-LOCATION_JITTER_DEGREES, LOCATION_JITTER_DEGREES));

        return build(card.cardId(), amount, merchant, location, null);
    }

    /** A transaction with exactly the given values, located at the city centre. */
    public Transaction manual(String cardId, long amount, Merchant merchant, City city, Instant timestamp) {
        return build(cardId, amount, merchant, city.location(), timestamp);
    }

    public static long roundToThousand(double amount) {
        return Math.round(amount / 1000.0) * 1000;
    }

    private Transaction build(String cardId, long amount, Merchant merchant, Location location, Instant timestamp) {
        String transactionId = "tx-%06d".formatted(sequence.incrementAndGet());
        return new Transaction(transactionId, cardId, amount, merchant.name(), location,
                cardClock.next(cardId, timestamp));
    }
}
