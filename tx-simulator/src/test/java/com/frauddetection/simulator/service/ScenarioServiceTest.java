package com.frauddetection.simulator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.frauddetection.common.CardProfiles;
import com.frauddetection.common.City;
import com.frauddetection.common.Decision;
import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.HistoricalAverage;
import com.frauddetection.common.Transaction;
import com.frauddetection.simulator.service.ScenarioService.Report;
import com.frauddetection.simulator.service.ScenarioService.Scenario;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** Checks what each scenario sends; decision-api is replaced by a stub that blocks from the 6th call. */
class ScenarioServiceTest {

    private final SimulationService simulation = mock(SimulationService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final CardClock cardClock = new CardClock(Clock.systemUTC());
    private final List<Transaction> sent = new ArrayList<>();
    private ScenarioService service;

    @BeforeEach
    void setUp() {
        when(simulation.submit(any())).thenAnswer(inv -> {
            Transaction tx = inv.getArgument(0);
            sent.add(tx);
            Decision decision = sent.size() >= 6 ? Decision.CHAN : Decision.CHO_QUA;
            String rule = decision == Decision.CHAN ? "qua_nhieu_giao_dich" : null;
            return new DecisionResult(tx.transactionId(), decision, rule == null ? 0.01 : null, rule, null, 5);
        });
        service = new ScenarioService(new TransactionGenerator(cardClock), simulation, cardClock, messaging, 0);
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
    void impossibleTravelGoesFromHanoiToHoChiMinhCityTwoMinutesLater() {
        Report report = service.runAndWait(Scenario.IMPOSSIBLE_TRAVEL);

        assertThat(sent).hasSize(2);
        assertThat(CardProfiles.find(report.run().cardId()).orElseThrow().homeCity()).isEqualTo(City.HA_NOI);
        assertThat(sent.get(0).location()).isEqualTo(City.HA_NOI.location());
        assertThat(sent.get(1).location()).isEqualTo(City.HO_CHI_MINH.location());
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
    void unknownSlugIsRejected() {
        assertThat(Scenario.fromSlug("rapid-fire")).contains(Scenario.RAPID_FIRE);
        assertThat(Scenario.fromSlug("teleport")).isEmpty();
    }
}
