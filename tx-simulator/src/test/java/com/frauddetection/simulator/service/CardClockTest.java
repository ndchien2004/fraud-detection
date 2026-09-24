package com.frauddetection.simulator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CardClockTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
    private final CardClock cardClock = new CardClock(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void usesNowWhenNoTimestampRequested() {
        assertThat(cardClock.next("card-0001", null)).isEqualTo(NOW);
    }

    @Test
    void acceptsFutureTimestampAndNeverGoesBackwards() {
        Instant twoMinutesLater = NOW.plusSeconds(120);
        assertThat(cardClock.next("card-0001", twoMinutesLater)).isEqualTo(twoMinutesLater);

        // real time is still NOW, but the card's clock already moved to +2 min
        assertThat(cardClock.next("card-0001", null)).isEqualTo(twoMinutesLater.plusMillis(1));
    }

    @Test
    void cardsHaveIndependentClocks() {
        cardClock.next("card-0001", NOW.plusSeconds(3600));
        assertThat(cardClock.next("card-0002", null)).isEqualTo(NOW);
    }
}
