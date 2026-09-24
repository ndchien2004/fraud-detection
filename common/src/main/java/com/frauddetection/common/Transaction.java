package com.frauddetection.common;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;

/**
 * One card transaction, as sent to the Kafka topic {@code transactions} and to {@code POST /check-transaction}.
 *
 * @param amount    amount in VND (no decimals)
 * @param merchant  merchant code, see {@link Merchant}
 * @param timestamp event time of the transaction, serialized as ISO-8601 (e.g. "2026-08-19T10:15:32Z")
 */
public record Transaction(
        String transactionId,
        String cardId,
        long amount,
        String merchant,
        Location location,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {
}
