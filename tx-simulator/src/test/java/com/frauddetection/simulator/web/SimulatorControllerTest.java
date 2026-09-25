package com.frauddetection.simulator.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.frauddetection.common.Decision;
import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.Transaction;
import com.frauddetection.simulator.service.AutoModeService;
import com.frauddetection.simulator.service.CardClock;
import com.frauddetection.simulator.service.ScenarioService;
import com.frauddetection.simulator.service.SimulationService;
import com.frauddetection.simulator.service.SimulatorStats;
import com.frauddetection.simulator.service.TransactionGenerator;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SimulatorController.class)
@Import({TransactionGenerator.class, CardClock.class, SimulatorStats.class})
class SimulatorControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SimulationService simulation;

    @MockitoBean
    ScenarioService scenarios;

    @MockitoBean
    AutoModeService autoMode;

    @MockitoBean
    Clock clock;

    @Test
    void manualTransactionReturnsTheDecisionAndLocationComesFromCity() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-08-19T10:15:32Z"));
        when(simulation.submit(any())).thenAnswer(inv -> {
            Transaction tx = inv.getArgument(0);
            return new DecisionResult(tx.transactionId(), Decision.XEM_XET, null, "chi_tieu_qua_cao_tuyet_doi", null, 12);
        });

        mvc.perform(post("/simulator/manual-transaction").contentType(MediaType.APPLICATION_JSON).content("""
                        {"cardId":"card-0007","amount":60000000,"merchant":"ATM","city":"HA_NOI"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.cardId").value("card-0007"))
                .andExpect(jsonPath("$.transaction.location.lat").value(21.0285))
                .andExpect(jsonPath("$.transaction.timestamp").value("2026-08-19T10:15:32Z"))
                .andExpect(jsonPath("$.result.decision").value("XEM_XET"))
                .andExpect(jsonPath("$.result.triggeredRule").value("chi_tieu_qua_cao_tuyet_doi"));
    }

    @Test
    void decisionApiDownGives503() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-08-19T10:15:32Z"));
        SimulationService.DecisionApiUnavailableException down = org.mockito.Mockito.mock(
                SimulationService.DecisionApiUnavailableException.class);
        when(simulation.submit(any())).thenThrow(down);

        mvc.perform(post("/simulator/manual-transaction").contentType(MediaType.APPLICATION_JSON).content("""
                        {"cardId":"card-0007","amount":1000,"merchant":"ATM","city":"HA_NOI"}
                        """))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void unknownCityIsRejected() throws Exception {
        mvc.perform(post("/simulator/manual-transaction").contentType(MediaType.APPLICATION_JSON).content("""
                        {"cardId":"card-0007","amount":1000,"merchant":"ATM","city":"ATLANTIS"}
                        """))
                .andExpect(status().isBadRequest());
        verify(simulation, never()).submit(any());
    }

    @Test
    void negativeAmountAndTooHighRateAreRejected() throws Exception {
        mvc.perform(post("/simulator/manual-transaction").contentType(MediaType.APPLICATION_JSON).content("""
                        {"cardId":"card-0007","amount":-5,"merchant":"ATM","city":"HA_NOI"}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/simulator/auto-mode").contentType(MediaType.APPLICATION_JSON).content("""
                        {"enabled":true,"ratePerSecond":1000}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scenarioStartsInTheBackgroundAndUnknownOnesAre404() throws Exception {
        when(scenarios.start(ScenarioService.Scenario.RAPID_FIRE)).thenReturn(
                new ScenarioService.Run("run-1", "rapid-fire", "Quẹt dồn dập", "...", "card-0003", 10));

        mvc.perform(post("/simulator/scenario/rapid-fire"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.totalSteps").value(10));
        mvc.perform(post("/simulator/scenario/teleport"))
                .andExpect(status().isNotFound());
    }

    @Test
    void catalogListsTheTwentyDemoCardsWithTheirAverage() throws Exception {
        mvc.perform(get("/simulator/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(20))
                .andExpect(jsonPath("$.cards[0].historicalAverage").isNumber())
                .andExpect(jsonPath("$.cities[0].code").value("HA_NOI"))
                .andExpect(jsonPath("$.cities[0].location.lat").value(21.0285));
    }
}
