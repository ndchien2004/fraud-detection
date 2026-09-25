package com.frauddetection.simulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.frauddetection.common.CardProfile;
import com.frauddetection.common.CardProfiles;
import com.frauddetection.common.City;
import com.frauddetection.common.Decision;
import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.FeatureCalculator;
import com.frauddetection.common.HistoricalAverage;
import com.frauddetection.common.Transaction;
import com.frauddetection.simulator.feature.FeatureServiceClient;
import com.frauddetection.simulator.feature.FeatureServiceClient.CardActivity;
import com.frauddetection.simulator.service.ScenarioService.Report;
import com.frauddetection.simulator.service.ScenarioService.Scenario;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** Checks what each scenario sends; decision-api is replaced by a stub that blocks from the 6th call. */
class ScenarioServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private final SimulationService simulation = mock(SimulationService.class);
    private final FeatureServiceClient featureService = mock(FeatureServiceClient.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CardClock cardClock = new CardClock(clock);
    private final List<Transaction> sent = new ArrayList<>();
    private ScenarioService service;

    @BeforeEach
    void setUp() {
        when(featureService.lastActivity(anyString())).thenReturn(Optional.empty());
        when(simulation.submit(any())).thenAnswer(inv -> {
            Transaction tx = inv.getArgument(0);
            sent.add(tx);
            Decision decision = sent.size() >= 6 ? Decision.CHAN : Decision.CHO_QUA;
            String rule = decision == Decision.CHAN ? "qua_nhieu_giao_dich" : null;
            return new DecisionResult(tx.transactionId(), decision, rule == null ? 0.01 : null, rule, null, 5);
        });
        service = new ScenarioService(new TransactionGenerator(cardClock), simulation, cardClock, featureService,
                clock, messaging, 0);
    }

    @Test
    void rapidFireSendsTenTransactionsOnOneCardAndReportsTheFirstDetection() {
        Report report = service.runAndWait(Scenario.RAPID_FIRE);

        assertThat(sent).hasSize(10);
        assertThat(sent).extracting(Transaction::cardId).containsOnly(report.run().cardId());
        assertThat(report.firstDetectedStep()).isEqualTo(6);
        assertThat(report.summary()).contains("thứ 6/10").contains("qua_nhieu_giao_dich");
        verify(messaging, atLeastOnce()).convertAndSend(eq(ScenarioService.TOPIC), any(ScenarioService.Event.class));
    }

    @Test
    void impossibleTravelGoesFromHomeToAFarCityTwoMinutesLater() {
        Report report = service.runAndWait(Scenario.IMPOSSIBLE_TRAVEL);

        CardProfile card = CardProfiles.find(report.run().cardId()).orElseThrow();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).location()).isEqualTo(card.homeCity().location());
        assertThat(FeatureCalculator.haversineKm(sent.get(0).location(), sent.get(1).location())).isGreaterThan(600);
        assertThat(Duration.between(sent.get(0).timestamp(), sent.get(1).timestamp())).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void unusualAmountIsTwentyTimesTheHistoricalAverage() {
        Report report = service.runAndWait(Scenario.UNUSUAL_AMOUNT);

        double avg = HistoricalAverage.of(CardProfiles.find(report.run().cardId()).orElseThrow());
        assertThat(sent).hasSize(1);
        assertThat((double) sent.get(0).amount()).isBetween(avg * 20 - 1000, avg * 20 + 1000);
    }

    @Test
    void consecutiveRunsPickDifferentCards() {
        String first = service.runAndWait(Scenario.UNUSUAL_AMOUNT).run().cardId();
        String second = service.runAndWait(Scenario.UNUSUAL_AMOUNT).run().cardId();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void scenariosRunOnFreshScenarioCards() {
        Report report = service.runAndWait(Scenario.RAPID_FIRE);
        assertThat(report.run().cardId()).startsWith("sc-");
    }

    @Test
    void cardsWithRecentActivityAreSkipped() {
        // every scenario card paid somewhere a minute ago, except those whose number ends in 7
        when(featureService.lastActivity(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return id.endsWith("7") ? Optional.empty()
                    : Optional.of(new CardActivity(NOW.minusSeconds(60), City.TOKYO.location()));
        });

        assertThat(service.pickCleanCard().cardId()).endsWith("7");
    }

    @Test
    void afterARestartTimestampsContinueAfterTheCardsRealHistory() {
        Instant future = NOW.plus(Duration.ofMinutes(2)); // left by a scenario before the restart
        when(featureService.lastActivity(anyString())).thenReturn(
                Optional.of(new CardActivity(future, City.HO_CHI_MINH.location())));

        service.runAndWait(Scenario.UNUSUAL_AMOUNT);

        assertThat(sent.get(0).timestamp()).isAfter(future);
    }

    @Test
    void cleanlinessRules() {
        assertThat(ScenarioService.isClean(Optional.empty(), NOW)).isTrue();
        assertThat(ScenarioService.isClean(activity(NOW.minus(Duration.ofHours(3)), City.TOKYO), NOW)).isTrue();
        assertThat(ScenarioService.isClean(activity(NOW.minus(Duration.ofMinutes(90)), City.HA_NOI), NOW)).isFalse();
        assertThat(ScenarioService.isClean(activity(NOW.plusSeconds(60), City.HA_NOI), NOW)).isFalse();
    }

    private static Optional<CardActivity> activity(Instant at, City city) {
        return Optional.of(new CardActivity(at, city.location()));
    }

    @Test
    void unknownSlugIsRejected() {
        assertThat(Scenario.fromSlug("rapid-fire")).contains(Scenario.RAPID_FIRE);
        assertThat(Scenario.fromSlug("teleport")).isEmpty();
    }
}
