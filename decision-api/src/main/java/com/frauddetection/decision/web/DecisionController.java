package com.frauddetection.decision.web;

import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.Location;
import com.frauddetection.common.Transaction;
import com.frauddetection.decision.service.DecisionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The public API of the fraud system (section 7 of the spec). */
@RestController
public class DecisionController {

    private final DecisionService decisionService;
    private final Clock clock;

    public DecisionController(DecisionService decisionService, Clock clock) {
        this.decisionService = decisionService;
        this.clock = clock;
    }

    @PostMapping("/check-transaction")
    public DecisionResult check(@Valid @RequestBody CheckTransactionRequest request) {
        Instant timestamp = request.timestamp() != null
                ? request.timestamp()
                : Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        Transaction tx = new Transaction(request.transactionId(), request.cardId(), request.amount(),
                request.merchant(), request.location(), timestamp);
        return decisionService.check(tx);
    }

    /** Same shape as {@link Transaction}, validated; {@code timestamp} defaults to now. */
    public record CheckTransactionRequest(
            @NotBlank String transactionId,
            @NotBlank String cardId,
            @NotNull @Positive Long amount,
            @NotBlank String merchant,
            @NotNull Location location,
            Instant timestamp) {
    }
}
