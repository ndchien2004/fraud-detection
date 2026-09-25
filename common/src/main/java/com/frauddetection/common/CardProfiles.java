package com.frauddetection.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The simulated cards. Values are fixed (or derived from the card number) so every service sees
 * the same cards.
 * <ul>
 *   <li>{@link #DEMO}: card-0001 .. card-0020, for manual transactions and fraud scenarios</li>
 *   <li>background cards bg-00001 .. bg-50000, for auto mode traffic: with the rule "more than 5
 *       transactions in 5 minutes", 20 cards could only carry ~0.3 tx/s before every card is
 *       blocked; 50,000 cards carry 500 tx/s at ~3 transactions per card per 5 minutes</li>
 * </ul>
 */
public final class CardProfiles {

    public static final int BACKGROUND_COUNT = 50_000;

    private static final City[] HOME_CITIES = {
            City.HA_NOI, City.HO_CHI_MINH, City.DA_NANG, City.HAI_PHONG, City.CAN_THO
    };

    private static final long[] TYPICAL_AMOUNTS = {
            150_000, 250_000, 400_000, 600_000, 800_000,
            1_000_000, 1_200_000, 1_500_000, 2_000_000, 2_500_000,
            180_000, 300_000, 450_000, 700_000, 900_000,
            1_100_000, 1_300_000, 1_800_000, 2_200_000, 3_000_000
    };

    private static final Pattern BACKGROUND_ID = Pattern.compile("bg-(\\d{5})");

    public static final List<CardProfile> DEMO = buildDemo();

    private CardProfiles() {
    }

    private static List<CardProfile> buildDemo() {
        List<CardProfile> cards = new ArrayList<>();
        for (int i = 0; i < TYPICAL_AMOUNTS.length; i++) {
            String cardId = "card-%04d".formatted(i + 1);
            cards.add(new CardProfile(cardId, HOME_CITIES[i % HOME_CITIES.length], TYPICAL_AMOUNTS[i]));
        }
        return Collections.unmodifiableList(cards);
    }

    /**
     * Background card number {@code n} (1 .. {@link #BACKGROUND_COUNT}). Its typical amount is
     * derived from {@code n}, log-uniform between 100,000 and 3,000,000 VND.
     */
    public static CardProfile background(int n) {
        if (n < 1 || n > BACKGROUND_COUNT) {
            throw new IllegalArgumentException("Background card number out of range: " + n);
        }
        double u = new Random(n).nextDouble();
        long typical = Math.round(Math.exp(Math.log(100_000) + u * (Math.log(3_000_000) - Math.log(100_000))) / 1000) * 1000;
        return new CardProfile("bg-%05d".formatted(n), HOME_CITIES[n % HOME_CITIES.length], typical);
    }

    /** Finds a demo or background card. */
    public static Optional<CardProfile> find(String cardId) {
        if (cardId == null) {
            return Optional.empty();
        }
        Matcher m = BACKGROUND_ID.matcher(cardId);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(1));
            return n >= 1 && n <= BACKGROUND_COUNT ? Optional.of(background(n)) : Optional.empty();
        }
        return DEMO.stream().filter(c -> c.cardId().equals(cardId)).findFirst();
    }
}
