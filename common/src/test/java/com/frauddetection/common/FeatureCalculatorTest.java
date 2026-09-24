package com.frauddetection.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FeatureCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-08-19T10:00:00Z");

    private static Transaction tx(Instant at, long amount, Location location) {
        return new Transaction("tx", "card-0001", amount, "GRAB", location, at);
    }

    @Test
    void haversineHanoiToHoChiMinhCity() {
        double km = FeatureCalculator.haversineKm(City.HA_NOI.location(), City.HO_CHI_MINH.location());
        assertThat(km).isBetween(1130.0, 1145.0);
    }

    @Test
    void hanoiToHoChiMinhCityInTwoMinutesIsImpossible() {
        Location hn = City.HA_NOI.location();
        Location hcm = City.HO_CHI_MINH.location();
        assertThat(FeatureCalculator.isImpossibleTravel(hn, T0, hcm, T0.plus(Duration.ofMinutes(2)))).isTrue();
        // ~1138 km in 2 minutes
        assertThat(FeatureCalculator.impliedSpeedKmh(1138, Duration.ofMinutes(2).toMillis()))
                .isCloseTo(34_140, within(10.0));
    }

    @Test
    void hanoiToHaiPhongInThreeHoursIsFine() {
        assertThat(FeatureCalculator.isImpossibleTravel(
                City.HA_NOI.location(), T0, City.HAI_PHONG.location(), T0.plus(Duration.ofHours(3)))).isFalse();
    }

    @Test
    void hanoiToHoChiMinhCityInTwoHoursIsAPlaneSoFine() {
        assertThat(FeatureCalculator.isImpossibleTravel(
                City.HA_NOI.location(), T0, City.HO_CHI_MINH.location(), T0.plus(Duration.ofHours(2)))).isFalse();
    }

    @Test
    void shortHopsInsideOneCityAreNeverFlaggedEvenOneSecondApart() {
        Location a = new Location(21.0285, 105.8542);
        Location b = new Location(21.0500, 105.8700); // ~3 km away
        assertThat(FeatureCalculator.isImpossibleTravel(a, T0, b, T0.plusSeconds(1))).isFalse();
    }

    @Test
    void farApartAtTheSameInstantIsImpossible() {
        assertThat(FeatureCalculator.isImpossibleTravel(
                City.HA_NOI.location(), T0, City.SINGAPORE.location(), T0)).isTrue();
    }

    @Test
    void deviationOfTwentyTimesTheAverageIsNineteen() {
        assertThat(FeatureCalculator.deviationFromAverage(20_000_000, 1_000_000)).isEqualTo(19.0);
        assertThat(FeatureCalculator.deviationFromAverage(500_000, 1_000_000)).isEqualTo(-0.5);
        assertThat(FeatureCalculator.deviationFromAverage(500_000, 0)).isZero();
    }

    @Test
    void firstTransactionOfACardCountsItself() {
        Features f = FeatureCalculator.compute(null, 1_000_000, tx(T0, 3_000_000, City.HA_NOI.location()));

        assertThat(f.soGiaoDich5Phut()).isEqualTo(1);
        assertThat(f.tongTien1Gio()).isEqualTo(3_000_000);
        assertThat(f.trungBinhLichSu()).isEqualTo(1_000_000);
        assertThat(f.lechSoVoiTrungBinh()).isEqualTo(2.0);
        assertThat(f.khoangCachBatThuong()).isFalse();
    }

    @Test
    void sixthRapidTransactionSeesFivePreviousOnes() {
        CardState state = CardState.empty();
        for (int i = 0; i < 5; i++) {
            state = state.add(tx(T0.plusSeconds(i), 100_000, City.HA_NOI.location()));
        }
        Features f = FeatureCalculator.compute(state, 100_000, tx(T0.plusSeconds(5), 100_000, City.HA_NOI.location()));

        assertThat(f.soGiaoDich5Phut()).isEqualTo(6);
        assertThat(f.tongTien1Gio()).isEqualTo(600_000);
    }

    @Test
    void impossibleTravelIsDetectedAgainstThePreviousTransaction() {
        CardState state = CardState.empty().add(tx(T0, 100_000, City.HA_NOI.location()));
        Features f = FeatureCalculator.compute(state, 100_000,
                tx(T0.plus(Duration.ofMinutes(2)), 100_000, City.HO_CHI_MINH.location()));

        assertThat(f.khoangCachBatThuong()).isTrue();
    }
}
