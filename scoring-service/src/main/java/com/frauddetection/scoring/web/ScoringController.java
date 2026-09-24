package com.frauddetection.scoring.web;

import com.frauddetection.common.Decision;
import com.frauddetection.common.ScoreResult;
import com.frauddetection.common.Transaction;
import com.frauddetection.scoring.rules.RuleEngine;
import com.frauddetection.scoring.rules.RulesConfig.MlThresholds;
import com.frauddetection.scoring.service.ScoringService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ScoringController {

    private final ScoringService scoringService;
    private final RuleEngine ruleEngine;

    public ScoringController(ScoringService scoringService, RuleEngine ruleEngine) {
        this.scoringService = scoringService;
        this.ruleEngine = ruleEngine;
    }

    /** Internal API called by decision-api. */
    @PostMapping("/score")
    public ScoreResult score(@RequestBody Transaction tx) {
        if (tx.cardId() == null || tx.location() == null || tx.timestamp() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cardId, location and timestamp are required");
        }
        return scoringService.score(tx);
    }

    /** The rules currently in effect, to check that an edit of rules.yaml was picked up. */
    @GetMapping("/admin/rules")
    public RulesView rules() {
        RuleEngine.Snapshot s = ruleEngine.snapshot();
        List<RuleView> rules = s.rules().stream()
                .map(r -> new RuleView(r.name(), r.condition(), r.action()))
                .toList();
        return new RulesView(rules, s.thresholds(), s.loadedAt(), scoringService.modelName());
    }

    /** Force a reload now instead of waiting for the periodic check. */
    @PostMapping("/admin/reload-rules")
    public RulesView reload() {
        if (!ruleEngine.reload()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "rules.yaml is invalid, previous rules kept (see scoring-service log)");
        }
        return rules();
    }

    public record RuleView(String name, String condition, Decision action) {
    }

    public record RulesView(List<RuleView> rules, MlThresholds mlThresholds, Instant loadedAt, String model) {
    }
}
