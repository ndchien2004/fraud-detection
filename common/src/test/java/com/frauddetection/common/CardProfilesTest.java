package com.frauddetection.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CardProfilesTest {

    @Test
    void hasTwentyDemoCardsWithSequentialIds() {
        assertThat(CardProfiles.DEMO).hasSize(20);
        assertThat(CardProfiles.DEMO.get(0).cardId()).isEqualTo("card-0001");
        assertThat(CardProfiles.DEMO.get(19).cardId()).isEqualTo("card-0020");
    }

    @Test
    void findsDemoAndBackgroundCardsById() {
        assertThat(CardProfiles.find("card-0007")).isPresent();
        assertThat(CardProfiles.find("bg-00042")).contains(CardProfiles.background(42));
        assertThat(CardProfiles.find("bg-50000")).isPresent();
        assertThat(CardProfiles.find("bg-50001")).isEmpty();
        assertThat(CardProfiles.find("card-9999")).isEmpty();
        assertThat(CardProfiles.find(null)).isEmpty();
    }

    @Test
    void backgroundCardsAreStableAndInRange() {
        for (int n = 1; n <= 1000; n++) {
            CardProfile card = CardProfiles.background(n);
            assertThat(card).isEqualTo(CardProfiles.background(n));
            assertThat(card.typicalAmount()).isBetween(100_000L, 3_000_000L);
        }
    }

    @Test
    void historicalAverageIsStableAndCloseToTypicalAmount() {
        for (CardProfile card : CardProfiles.DEMO) {
            double avg = HistoricalAverage.of(card);
            assertThat(avg).isEqualTo(HistoricalAverage.of(card));
            assertThat(avg).isBetween(card.typicalAmount() * 0.8, card.typicalAmount() * 1.3);
        }
    }

    @Test
    void looksUpCityAndMerchantCaseInsensitively() {
        assertThat(City.fromCode("ha_noi")).contains(City.HA_NOI);
        assertThat(Merchant.fromCode("atm")).contains(Merchant.ATM);
        assertThat(City.fromCode("atlantis")).isEmpty();
    }
}
