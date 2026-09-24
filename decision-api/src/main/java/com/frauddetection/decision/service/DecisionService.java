package com.frauddetection.decision.service;

import com.frauddetection.common.Decision;
import com.frauddetection.common.DecisionEvent;
import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.ScoreResult;
import com.frauddetection.common.Transaction;
import com.frauddetection.decision.kafka.DecisionPublisher;
import com.frauddetection.decision.scoring.ScoringClient;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Orchestrates one check: score, measure latency, record in Kafka, answer. */
@Service
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);
    /** Reported as triggeredRule when the scoring service could not be reached. */
    public static final String SCORING_UNAVAILABLE = "scoring_unavailable";

    private final ScoringClient scoringClient;
    private final DecisionPublisher publisher;
    private final Clock clock;

    public DecisionService(ScoringClient scoringClient, DecisionPublisher publisher, Clock clock) {
        this.scoringClient = scoringClient;
        this.publisher = publisher;
        this.clock = clock;
    }

    public DecisionResult check(Transaction tx) {
        long start = System.nanoTime();
        ScoreResult score = scoreOrFallback(tx);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        DecisionResult result = new DecisionResult(tx.transactionId(), score.decision(), score.riskScore(),
                score.triggeredRule(), score.features(), latencyMs);
        publisher.publish(tx, new DecisionEvent(tx, result, Instant.now(clock)));
        return result;
    }

    /**
     * If scoring is down or too slow, neither approve blindly (fraud gets through) nor block
     * everything (every customer is stuck): send the transaction to manual review.
     */
    private ScoreResult scoreOrFallback(Transaction tx) {
        try {
            return scoringClient.score(tx);
        } catch (RuntimeException e) {
            log.warn("Scoring failed for {}, falling back to XEM_XET: {}", tx.transactionId(), e.getMessage());
            return new ScoreResult(Decision.XEM_XET, null, SCORING_UNAVAILABLE, null);
        }
    }
}
