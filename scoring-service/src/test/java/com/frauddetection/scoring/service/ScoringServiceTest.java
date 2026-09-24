package com.frauddetection.scoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.frauddetection.common.CardState;
import com.frauddetection.common.City;
import com.frauddetection.common.Decision;
import com.frauddetection.common.ScoreResult;
import com.frauddetection.common.Transaction;
import com.frauddetection.scoring.model.WeightedScoreModel;
import com.frauddetection.scoring.rules.RuleEngine;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the real config/rules.yaml of the repository against an in-memory "Redis". */
class ScoringServiceTest {

    private static final Instant T0 = Instant.parse("2026-08-19T10:00:00Z");
    private static final double AVG = 1_000_000;

    private final Map<String, CardState> states = new HashMap<>();
    private ScoringService service;

    @BeforeEach
    void setUp() {
        CardFeatureSource source = new CardFeatureSource() {
            @Override
            public Optional<CardState> state(String cardId) {
                return Optional.ofNullable(states.get(cardId));
            }

            @Override
            public double average(String cardId) {
                return AVG;
            }
        };
        service = new ScoringService(source, new RuleEngine(Path.of("../config/rules.yaml")), new WeightedScoreModel());
    }

    private static Transaction tx(Instant at, long amount, City city) {
        return new Transaction("tx", "card-0001", amount, "GRAB", city.location(), at);
    }

    /** Simulates the feature service having processed these transactions already. */
    private void history(Transaction... transactions) {
        CardState state = CardState.empty();
        for (Transaction t : transactions) {
            state = state.add(t);
        }
        states.put("card-0001", state);
    }

    @Test
    void normalPurchaseIsAllowed() {
        ScoreResult r = service.score(tx(T0, 900_000, City.HA_NOI));

        assertThat(r.decision()).isEqualTo(Decision.CHO_QUA);
        assertThat(r.triggeredRule()).isNull();
        assertThat(r.riskScore()).isLessThan(0.4);
    }

    @Test
    void sixthTransactionInFiveMinutesIsBlocked() {
        Transaction[] previous = new Transaction[5];
        for (int i = 0; i < 5; i++) {
            previous[i] = tx(T0.plusSeconds(i), 100_000, City.HA_NOI);
        }
        history(previous);

        ScoreResult r = service.score(tx(T0.plusSeconds(5), 100_000, City.HA_NOI));

        assertThat(r.decision()).isEqualTo(Decision.CHAN);
        assertThat(r.triggeredRule()).isEqualTo("qua_nhieu_giao_dich");
        assertThat(r.riskScore()).isNull();
        assertThat(r.features().soGiaoDich5Phut()).isEqualTo(6);
    }

    @Test
    void hanoiThenHoChiMinhCityTwoMinutesLaterIsBlocked() {
        history(tx(T0, 100_000, City.HA_NOI));

        ScoreResult r = service.score(tx(T0.plus(Duration.ofMinutes(2)), 100_000, City.HO_CHI_MINH));

        assertThat(r.decision()).isEqualTo(Decision.CHAN);
        assertThat(r.triggeredRule()).isEqualTo("di_chuyen_bat_kha_thi");
    }

    @Test
    void amountAboveFiftyMillionIsReviewed() {
        ScoreResult r = service.score(tx(T0, 60_000_000, City.HA_NOI));

        assertThat(r.decision()).isEqualTo(Decision.XEM_XET);
        assertThat(r.triggeredRule()).isEqualTo("chi_tieu_qua_cao_tuyet_doi");
    }

    @Test
    void twentyTimesTheAverageIsReviewedByTheModel() {
        ScoreResult r = service.score(tx(T0, 20_000_000, City.HA_NOI));

        assertThat(r.triggeredRule()).isNull();
        assertThat(r.riskScore()).isBetween(0.4, 0.8);
        assertThat(r.decision()).isEqualTo(Decision.XEM_XET);
    }
}
