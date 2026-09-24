package com.frauddetection.scoring.rules;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hot reload: picks up edits to rules.yaml without restarting the service. */
@Component
class RulesReloader {

    private final RuleEngine ruleEngine;

    RulesReloader(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    @Scheduled(fixedDelayString = "${app.rules.reload-interval-ms}")
    void reloadIfChanged() {
        ruleEngine.reloadIfChanged();
    }
}
