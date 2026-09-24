package com.frauddetection.simulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.frauddetection.common.CardProfile;
import com.frauddetection.common.CardProfiles;
import com.frauddetection.common.City;
import com.frauddetection.common.Merchant;
import com.frauddetection.common.Transaction;
import java.time.Clock;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class TransactionGeneratorTest {

    private final TransactionGenerator generator = new TransactionGenerator(new CardClock(Clock.systemUTC()));

    @RepeatedTest(50)
    void randomTransactionIsPlausibleForItsCard() {
        Transaction tx = generator.random();
        CardProfile card = CardProfiles.find(tx.cardId()).orElseThrow();

        assertThat(tx.amount()).isPositive().isLessThan(card.typicalAmount() * 20);
        assertThat(Merchant.fromCode(tx.merchant())).isPresent();
        assertThat(tx.location().lat()).isCloseTo(card.homeCity().location().lat(), within(0.05));
        assertThat(tx.location().lon()).isCloseTo(card.homeCity().location().lon(), within(0.05));
    }

    @Test
    void manualTransactionKeepsGivenValuesAndGetsSequentialIds() {
        Transaction first = generator.manual("card-0007", 25_000_000, Merchant.ATM, City.HA_NOI, null);
        Transaction second = generator.manual("card-0007", 100_000, Merchant.GRAB, City.HA_NOI, null);

        assertThat(first.amount()).isEqualTo(25_000_000);
        assertThat(first.merchant()).isEqualTo("ATM");
        assertThat(first.location()).isEqualTo(City.HA_NOI.location());
        assertThat(first.transactionId()).isEqualTo("tx-000001");
        assertThat(second.transactionId()).isEqualTo("tx-000002");
        assertThat(second.timestamp()).isAfter(first.timestamp());
    }
}
