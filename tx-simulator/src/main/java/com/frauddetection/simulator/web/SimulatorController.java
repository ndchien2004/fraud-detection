package com.frauddetection.simulator.web;

import com.frauddetection.common.CardProfiles;
import com.frauddetection.common.City;
import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.Merchant;
import com.frauddetection.common.Transaction;
import com.frauddetection.simulator.service.AutoModeService;
import com.frauddetection.simulator.service.ScenarioService;
import com.frauddetection.simulator.service.ScenarioService.Scenario;
import com.frauddetection.simulator.service.SimulationService;
import com.frauddetection.simulator.service.SimulationService.DecisionApiUnavailableException;
import com.frauddetection.simulator.service.SimulatorStats;
import com.frauddetection.simulator.service.TransactionGenerator;
import com.frauddetection.simulator.web.SimulatorDtos.AutoModeRequest;
import com.frauddetection.simulator.web.SimulatorDtos.CatalogResponse;
import com.frauddetection.simulator.web.SimulatorDtos.ManualTransactionRequest;
import com.frauddetection.simulator.web.SimulatorDtos.ManualTransactionResponse;
import com.frauddetection.simulator.web.SimulatorDtos.StatsResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/simulator")
public class SimulatorController {

    private final TransactionGenerator generator;
    private final SimulationService simulation;
    private final ScenarioService scenarios;
    private final AutoModeService autoMode;
    private final SimulatorStats stats;

    public SimulatorController(TransactionGenerator generator, SimulationService simulation,
                               ScenarioService scenarios, AutoModeService autoMode, SimulatorStats stats) {
        this.generator = generator;
        this.simulation = simulation;
        this.scenarios = scenarios;
        this.autoMode = autoMode;
        this.stats = stats;
    }

    /** Sends one transaction to decision-api and returns its decision right away. */
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
        DecisionResult result = simulation.submit(tx);
        return new ManualTransactionResponse(tx, result);
    }

    /**
     * Runs a preset scenario: rapid-fire, impossible-travel or unusual-amount.
     * By default it runs in the background (progress on WebSocket /topic/scenario) and answers 202;
     * with {@code ?wait=true} it answers 200 with the full report once finished.
     */
    @PostMapping("/scenario/{name}")
    public ResponseEntity<?> scenario(@PathVariable String name, @RequestParam(defaultValue = "false") boolean wait) {
        Scenario scenario = Scenario.fromSlug(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown scenario: " + name + " (rapid-fire, impossible-travel, unusual-amount)"));
        if (wait) {
            return ResponseEntity.ok(scenarios.runAndWait(scenario));
        }
        return ResponseEntity.accepted().body(scenarios.start(scenario));
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
        return new StatsResponse(stats.snapshot(), autoMode.status());
    }

    /** Values for the UI dropdowns (demo cards, merchants, cities). */
    @GetMapping("/catalog")
    public CatalogResponse catalog() {
        return CatalogResponse.create();
    }

    @ExceptionHandler(DecisionApiUnavailableException.class)
    ResponseEntity<ProblemDetail> decisionApiDown(DecisionApiUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                        "decision-api is not reachable. Is it running on port 8082?"));
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
