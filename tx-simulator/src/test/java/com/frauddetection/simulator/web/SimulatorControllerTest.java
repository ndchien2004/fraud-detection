package com.frauddetection.simulator.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.frauddetection.common.Transaction;
import com.frauddetection.simulator.kafka.TransactionPublisher;
import com.frauddetection.simulator.service.AutoModeService;
import com.frauddetection.simulator.service.CardClock;
import com.frauddetection.simulator.service.SimulatorStats;
import com.frauddetection.simulator.service.TransactionGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SimulatorController.class)
@Import({TransactionGenerator.class, CardClock.class, SimulatorStats.class})
class SimulatorControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TransactionPublisher publisher;

    @MockitoBean
    AutoModeService autoMode;

    @MockitoBean
    Clock clock;

    @Test
    void manualTransactionIsPublishedAndLocationComesFromCity() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-08-19T10:15:32Z"));
        when(publisher.publish(any())).thenAnswer(inv -> {
            Transaction tx = inv.getArgument(0);
            RecordMetadata meta = new RecordMetadata(new TopicPartition("transactions", 1), 42, 0, 0, 0, 0);
            return CompletableFuture.completedFuture(
                    new SendResult<>(new ProducerRecord<>("transactions", tx.cardId(), tx), meta));
        });

        mvc.perform(post("/simulator/manual-transaction").contentType(MediaType.APPLICATION_JSON).content("""
                        {"cardId":"card-0007","amount":25000000,"merchant":"ATM","city":"HA_NOI"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.cardId").value("card-0007"))
                .andExpect(jsonPath("$.transaction.location.lat").value(21.0285))
                .andExpect(jsonPath("$.transaction.timestamp").value("2026-08-19T10:15:32Z"))
                .andExpect(jsonPath("$.partition").value(1))
                .andExpect(jsonPath("$.offset").value(42));
    }

    @Test
    void unknownCityIsRejected() throws Exception {
        mvc.perform(post("/simulator/manual-transaction").contentType(MediaType.APPLICATION_JSON).content("""
                        {"cardId":"card-0007","amount":1000,"merchant":"ATM","city":"ATLANTIS"}
                        """))
                .andExpect(status().isBadRequest());
        verify(publisher, never()).publish(any());
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
    void catalogListsTwentyCards() throws Exception {
        mvc.perform(get("/simulator/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(20))
                .andExpect(jsonPath("$.cities[0].code").value("HA_NOI"));
    }
}
