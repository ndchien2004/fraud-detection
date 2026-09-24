package com.frauddetection.feature.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.frauddetection.common.CardProfile;
import com.frauddetection.common.CardProfiles;
import org.junit.jupiter.api.Test;

class HistorySeederTest {

    @Test
    void averageIsCloseToTheCardsTypicalAmount() {
        for (CardProfile card : CardProfiles.ALL) {
            double avg = HistorySeeder.historicalAverage(card);
            assertThat(avg).isBetween(card.typicalAmount() * 0.8, card.typicalAmount() * 1.3);
        }
    }

    @Test
    void averageIsTheSameOnEveryRestart() {
        CardProfile card = CardProfiles.find("card-0007").orElseThrow();
        assertThat(HistorySeeder.historicalAverage(card)).isEqualTo(HistorySeeder.historicalAverage(card));
    }
}
