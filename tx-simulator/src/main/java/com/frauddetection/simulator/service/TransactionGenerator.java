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

/** Builds transactions: random "normal" ones for auto mode, or exactly what the user asked for. */
@Component
public class TransactionGenerator {

    /** ~0.03 degrees is about 3 km: transactions scatter around the city centre. */
    private static final double LOCATION_JITTER_DEGREES = 0.03;
    /** Spread of the log-normal amount distribution around the card's typical amount. */
    private static final double AMOUNT_SIGMA = 0.4;
    private static final long MIN_AMOUNT = 10_000;

    private final CardClock cardClock;
    private final AtomicLong sequence = new AtomicLong();

    public TransactionGenerator(CardClock cardClock) {
        this.cardClock = cardClock;
    }

    /** A plausible transaction of a random card, in its home city, around its typical amount. */
    public Transaction random() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        CardProfile card = CardProfiles.ALL.get(rnd.nextInt(CardProfiles.ALL.size()));
        Merchant[] merchants = Merchant.values();
        Merchant merchant = merchants[rnd.nextInt(merchants.length)];

        double factor = Math.exp(rnd.nextGaussian() * AMOUNT_SIGMA);
        long amount = Math.max(MIN_AMOUNT, Math.round(card.typicalAmount() * factor / 1000.0) * 1000);

        Location home = card.homeCity().location();
        Location location = new Location(
                home.lat() + rnd.nextDouble(-LOCATION_JITTER_DEGREES, LOCATION_JITTER_DEGREES),
                home.lon() + rnd.nextDouble(-LOCATION_JITTER_DEGREES, LOCATION_JITTER_DEGREES));

        return build(card.cardId(), amount, merchant, location, null);
    }

    /** A transaction with exactly the given values, located at the city centre. */
    public Transaction manual(String cardId, long amount, Merchant merchant, City city, Instant timestamp) {
        return build(cardId, amount, merchant, city.location(), timestamp);
    }

    private Transaction build(String cardId, long amount, Merchant merchant, Location location, Instant timestamp) {
        String transactionId = "tx-%06d".formatted(sequence.incrementAndGet());
        return new Transaction(transactionId, cardId, amount, merchant.name(), location,
                cardClock.next(cardId, timestamp));
    }
}
