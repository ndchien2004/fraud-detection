package com.frauddetection.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DecisionTest {

    @Test
    void hasThreeVerdicts() {
        assertThat(Decision.values())
                .containsExactly(Decision.CHO_QUA, Decision.XEM_XET, Decision.CHAN);
    }
}
