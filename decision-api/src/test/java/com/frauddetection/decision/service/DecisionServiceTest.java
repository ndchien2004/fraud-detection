package com.frauddetection.decision.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.frauddetection.common.City;
import com.frauddetection.common.Decision;
import com.frauddetection.common.DecisionEvent;
import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.Features;
import com.frauddetection.common.ScoreResult;
import com.frauddetection.common.Transaction;
import com.frauddetection.decision.kafka.DecisionPublisher;
import com.frauddetection.decision.scoring.ScoringClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

class DecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T10:15:33Z");
    private static final Transaction TX = new Transaction("tx-000123", "card-0007", 25_000_000, "ATM",
            City.HA_NOI.location(), Instant.parse("2026-08-19T10:15:32Z"));

    private final ScoringClient scoringClient = mock(ScoringClient.class);
    private final DecisionPublisher publisher = mock(DecisionPublisher.class);
    private final DecisionService service =
            new DecisionService(scoringClient, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void returnsTheScoreAndRecordsTransactionAndDecisionInKafka() {
        Features features = new Features(2, 27_000_000, 1_000_000, 24.0, false);
        when(scoringClient.score(TX)).thenReturn(new ScoreResult(Decision.XEM_XET, 0.63, null, features));

        DecisionResult result = service.check(TX);

        assertThat(result.transactionId()).isEqualTo("tx-000123");
        assertThat(result.decision()).isEqualTo(Decision.XEM_XET);
        assertThat(result.riskScore()).isEqualTo(0.63);
        assertThat(result.features()).isEqualTo(features);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);

        ArgumentCaptor<DecisionEvent> event = ArgumentCaptor.forClass(DecisionEvent.class);
        verify(publisher).publish(eq(TX), event.capture());
        assertThat(event.getValue().result()).isEqualTo(result);
        assertThat(event.getValue().decidedAt()).isEqualTo(NOW);
    }

    @Test
    void fallsBackToReviewWhenScoringIsDown() {
        when(scoringClient.score(any())).thenThrow(new ResourceAccessException("Connection refused"));

        DecisionResult result = service.check(TX);

        assertThat(result.decision()).isEqualTo(Decision.XEM_XET);
        assertThat(result.triggeredRule()).isEqualTo(DecisionService.SCORING_UNAVAILABLE);
        verify(publisher).publish(eq(TX), any());
    }
}
