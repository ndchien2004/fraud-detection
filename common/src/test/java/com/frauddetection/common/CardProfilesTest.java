package com.frauddetection.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CardProfilesTest {

    @Test
    void hasTwentyCardsWithSequentialIds() {
        assertThat(CardProfiles.ALL).hasSize(20);
        assertThat(CardProfiles.ALL.get(0).cardId()).isEqualTo("card-0001");
        assertThat(CardProfiles.ALL.get(19).cardId()).isEqualTo("card-0020");
    }

    @Test
    void findsCardById() {
        assertThat(CardProfiles.find("card-0007")).isPresent();
        assertThat(CardProfiles.find("card-9999")).isEmpty();
    }

    @Test
    void looksUpCityAndMerchantCaseInsensitively() {
        assertThat(City.fromCode("ha_noi")).contains(City.HA_NOI);
        assertThat(Merchant.fromCode("atm")).contains(Merchant.ATM);
        assertThat(City.fromCode("atlantis")).isEmpty();
    }
}
