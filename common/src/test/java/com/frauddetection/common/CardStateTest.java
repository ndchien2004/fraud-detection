package com.frauddetection.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CardStateTest {

    private static final Instant T0 = Instant.parse("2026-08-19T10:00:00Z");
    private static final Duration FIVE_MIN = Duration.ofMinutes(5);
    private static final Duration ONE_HOUR = Duration.ofHours(1);

    private static Transaction tx(Instant at, long amount, City city) {
        return new Transaction("tx", "card-0001", amount, "GRAB", city.location(), at);
    }

    @Test
    void transactionsInSameTenSecondsShareOneBucket() {
        CardState state = CardState.empty()
                .add(tx(T0, 100, City.HA_NOI))
                .add(tx(T0.plusSeconds(3), 200, City.HA_NOI));

        assertThat(state.buckets()).containsExactly(new CardState.Bucket(T0.toEpochMilli(), 2, 300));
        assertThat(state.countInWindow(T0.plusSeconds(3), FIVE_MIN)).isEqualTo(2);
    }

    @Test
    void sixTransactionsWithinFiveMinutesAreCounted() {
        CardState state = CardState.empty();
        for (int i = 0; i < 6; i++) {
            state = state.add(tx(T0.plusSeconds(i * 30L), 1_000, City.HA_NOI));
        }
        Instant last = T0.plusSeconds(150);
        assertThat(state.countInWindow(last, FIVE_MIN)).isEqualTo(6);
        assertThat(state.sumInWindow(last, ONE_HOUR)).isEqualTo(6_000);
    }

    @Test
    void oldTransactionsLeaveTheFiveMinuteWindowButStayInTheHour() {
        CardState state = CardState.empty()
                .add(tx(T0, 1_000, City.HA_NOI))
                .add(tx(T0.plus(Duration.ofMinutes(20)), 2_000, City.HA_NOI));

        Instant at = T0.plus(Duration.ofMinutes(20));
        assertThat(state.countInWindow(at, FIVE_MIN)).isEqualTo(1);
        assertThat(state.sumInWindow(at, ONE_HOUR)).isEqualTo(3_000);
    }

    @Test
    void bucketsOlderThanOneHourAreDropped() {
        CardState state = CardState.empty()
                .add(tx(T0, 1_000, City.HA_NOI))
                .add(tx(T0.plus(Duration.ofHours(2)), 5_000, City.HA_NOI));

        assertThat(state.buckets()).hasSize(1);
        assertThat(state.sumInWindow(T0.plus(Duration.ofHours(2)), ONE_HOUR)).isEqualTo(5_000);
    }

    @Test
    void remembersLocationOfLatestTransactionEvenIfALateOneArrives() {
        CardState state = CardState.empty()
                .add(tx(T0.plusSeconds(60), 1_000, City.HO_CHI_MINH))
                .add(tx(T0, 1_000, City.HA_NOI)); // arrives late

        assertThat(state.lastLocation()).isEqualTo(City.HO_CHI_MINH.location());
        assertThat(state.lastTimestamp()).isEqualTo(T0.plusSeconds(60));
        assertThat(state.countInWindow(T0.plusSeconds(60), FIVE_MIN)).isEqualTo(2);
    }
}
