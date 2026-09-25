package com.frauddetection.scoring.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.frauddetection.common.City;
import com.frauddetection.common.Features;
import com.frauddetection.common.Transaction;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs the committed model-training/model.onnx from Java. */
class OnnxRiskModelTest {

    private static final double AVG = 1_000_000;
    private static OnnxRiskModel model;

    @BeforeAll
    static void load() {
        model = new OnnxRiskModel(Path.of("../model-training/model.onnx"));
    }

    @AfterAll
    static void close() throws Exception {
        model.close();
    }

    private static double score(long amount, int count5min, long hourTotal) {
        Transaction tx = new Transaction("tx", "card-0001", amount, "GRAB", City.HA_NOI.location(), Instant.now());
        Features f = new Features(count5min, hourTotal, AVG, (amount - AVG) / AVG, false);
        return model.score(tx, f);
    }

    @Test
    void normalPurchaseScoresLow() {
        assertThat(score(1_000_000, 1, 1_000_000)).isLessThan(0.4);
    }

    @Test
    void twentyTimesTheAverageScoresAtLeastReview() {
        assertThat(score(20_000_000, 1, 20_000_000)).isGreaterThan(0.4);
    }

    @Test
    void cardTestingBurstScoresHigh() {
        assertThat(score(15_000, 3, 45_000)).isGreaterThan(0.8);
    }

    @Test
    void reportsItsName() {
        assertThat(model.name()).isEqualTo("onnx:model.onnx");
    }
}
