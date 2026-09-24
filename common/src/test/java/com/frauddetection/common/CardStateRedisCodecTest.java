package com.frauddetection.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CardStateRedisCodecTest {

    @Test
    void roundTripsThroughARedisHash() {
        CardState state = CardState.empty()
                .add(new Transaction("tx-1", "card-0001", 100_000, "GRAB", City.HA_NOI.location(),
                        Instant.parse("2026-08-19T10:00:00Z")))
                .add(new Transaction("tx-2", "card-0001", 50_000, "GRAB", City.DA_NANG.location(),
                        Instant.parse("2026-08-19T10:00:30Z")));

        Map<String, String> hash = CardStateRedisCodec.toHash(state);

        assertThat(CardStateRedisCodec.fromHash(hash)).contains(state);
    }

    @Test
    void emptyHashMeansNoState() {
        assertThat(CardStateRedisCodec.fromHash(Map.of())).isEmpty();
        assertThat(CardStateRedisCodec.parseAverage(null)).isZero();
    }
}
