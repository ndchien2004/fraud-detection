package com.frauddetection.common;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/** Published to the Kafka topic {@code decisions} for every checked transaction (read by the dashboard). */
public record DecisionEvent(
        Transaction transaction,
        DecisionResult result,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant decidedAt) {
}
