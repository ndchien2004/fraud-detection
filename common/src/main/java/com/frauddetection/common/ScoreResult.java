package com.frauddetection.common;

/**
 * Response of the scoring service's {@code POST /score}.
 *
 * @param riskScore     ML score in [0, 1]; null when a rule matched first
 * @param triggeredRule name of the matching rule from rules.yaml, or null
 */
public record ScoreResult(Decision decision, Double riskScore, String triggeredRule, Features features) {
}
