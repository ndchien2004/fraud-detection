package com.frauddetection.simulator.web;

import com.frauddetection.common.CardProfiles;
import com.frauddetection.common.City;
import com.frauddetection.common.Merchant;
import com.frauddetection.common.Transaction;
import com.frauddetection.simulator.kafka.TransactionPublisher;
import com.frauddetection.simulator.service.AutoModeService;
import com.frauddetection.simulator.service.SimulatorStats;
import com.frauddetection.simulator.service.TransactionGenerator;
import com.frauddetection.simulator.web.SimulatorDtos.AutoModeRequest;
import com.frauddetection.simulator.web.SimulatorDtos.CatalogResponse;
import com.frauddetection.simulator.web.SimulatorDtos.ManualTransactionRequest;
import com.frauddetection.simulator.web.SimulatorDtos.ManualTransactionResponse;
import com.frauddetection.simulator.web.SimulatorDtos.StatsResponse;
import jakarta.validation.Valid;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/simulator")
public class SimulatorController {

    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final TransactionGenerator generator;
    private final TransactionPublisher publisher;
    private final AutoModeService autoMode;
    private final SimulatorStats stats;

    public SimulatorController(TransactionGenerator generator, TransactionPublisher publisher,
                               AutoModeService autoMode, SimulatorStats stats) {
        this.generator = generator;
        this.publisher = publisher;
        this.autoMode = autoMode;
        this.stats = stats;
    }

    /**
     * Phase 1: publishes the transaction and returns where Kafka stored it.
     * Phase 6 will call the Decision API first and return the decision instead.
     */
    @PostMapping("/manual-transaction")
    public ManualTransactionResponse manualTransaction(@Valid @RequestBody ManualTransactionRequest request) {
        if (CardProfiles.find(request.cardId()).isEmpty()) {
            throw badRequest("Unknown cardId: " + request.cardId());
        }
        Merchant merchant = Merchant.fromCode(request.merchant())
                .orElseThrow(() -> badRequest("Unknown merchant: " + request.merchant()));
        City city = City.fromCode(request.city())
                .orElseThrow(() -> badRequest("Unknown city: " + request.city()));

        Transaction tx = generator.manual(request.cardId(), request.amount(), merchant, city, request.timestamp());
        try {
            RecordMetadata meta = publisher.publish(tx).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS).getRecordMetadata();
            return new ManualTransactionResponse(tx, meta.topic(), meta.partition(), meta.offset());
        } catch (ExecutionException | TimeoutException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kafka is not reachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted", e);
        }
    }

    @PostMapping("/auto-mode")
    public AutoModeService.Status autoMode(@Valid @RequestBody AutoModeRequest request) {
        return autoMode.configure(request.enabled(), request.ratePerSecond());
    }

    @GetMapping("/auto-mode")
    public AutoModeService.Status autoModeStatus() {
        return autoMode.status();
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        return new StatsResponse(stats.sent(), stats.failed(), autoMode.status());
    }

    /** Values for the UI dropdowns (cards, merchants, cities). */
    @GetMapping("/catalog")
    public CatalogResponse catalog() {
        return CatalogResponse.create();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
