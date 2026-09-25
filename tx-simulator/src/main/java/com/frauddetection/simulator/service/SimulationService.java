package com.frauddetection.simulator.service;

import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.Transaction;
import com.frauddetection.simulator.decision.DecisionApiClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/** The single path every simulated transaction takes: ask decision-api, count, show in the live feed. */
@Service
public class SimulationService {

    private final DecisionApiClient decisionApi;
    private final SimulatorStats stats;
    private final LiveFeed liveFeed;

    public SimulationService(DecisionApiClient decisionApi, SimulatorStats stats, LiveFeed liveFeed) {
        this.decisionApi = decisionApi;
        this.stats = stats;
        this.liveFeed = liveFeed;
    }

    /** @throws DecisionApiUnavailableException when decision-api cannot be reached */
    public DecisionResult submit(Transaction tx) {
        DecisionResult result;
        try {
            result = decisionApi.check(tx);
        } catch (RestClientException e) {
            stats.recordFailed();
            throw new DecisionApiUnavailableException(e);
        }
        stats.recordDecision(result.decision());
        liveFeed.publish(tx, result);
        return result;
    }

    public static class DecisionApiUnavailableException extends RuntimeException {
        DecisionApiUnavailableException(Throwable cause) {
            super("decision-api is not reachable: " + cause.getMessage(), cause);
        }
    }
}
