package com.frauddetection.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** The 20 simulated cards card-0001 .. card-0020. Fixed values so every service sees the same cards. */
public final class CardProfiles {

    private static final City[] HOME_CITIES = {
            City.HA_NOI, City.HO_CHI_MINH, City.DA_NANG, City.HAI_PHONG, City.CAN_THO
    };

    private static final long[] TYPICAL_AMOUNTS = {
            150_000, 250_000, 400_000, 600_000, 800_000,
            1_000_000, 1_200_000, 1_500_000, 2_000_000, 2_500_000,
            180_000, 300_000, 450_000, 700_000, 900_000,
            1_100_000, 1_300_000, 1_800_000, 2_200_000, 3_000_000
    };

    public static final List<CardProfile> ALL = build();

    private CardProfiles() {
    }

    private static List<CardProfile> build() {
        List<CardProfile> cards = new ArrayList<>();
        for (int i = 0; i < TYPICAL_AMOUNTS.length; i++) {
            String cardId = "card-%04d".formatted(i + 1);
            cards.add(new CardProfile(cardId, HOME_CITIES[i % HOME_CITIES.length], TYPICAL_AMOUNTS[i]));
        }
        return Collections.unmodifiableList(cards);
    }

    public static Optional<CardProfile> find(String cardId) {
        return ALL.stream().filter(c -> c.cardId().equals(cardId)).findFirst();
    }
}
