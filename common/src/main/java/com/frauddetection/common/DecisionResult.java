package com.frauddetection.common;

/**
 * Response of {@code POST /check-transaction} (section 7 of the spec).
 *
 * @param riskScore     ML score in [0, 1]; null when a rule matched first and the model was not asked
 * @param triggeredRule name of the rule that matched, or null
 */
public record DecisionResult(
        String transactionId,
        Decision decision,
        Double riskScore,
        String triggeredRule,
        Features features,
        long latencyMs) {
}
