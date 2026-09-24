package com.frauddetection.scoring.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.common.Decision;
import java.util.List;

/** Java mirror of rules.yaml. */
public record RulesConfig(
        List<RuleDefinition> rules,
        @JsonProperty("ml_thresholds") MlThresholds mlThresholds) {

    /** @param condition a Spring Expression Language boolean expression, e.g. "so_giao_dich_5_phut > 5" */
    public record RuleDefinition(String name, String condition, Decision action) {
    }

    /** A model score strictly above a threshold leads to that decision. */
    public record MlThresholds(
            @JsonProperty("chan_neu_diem_tren") double chanAbove,
            @JsonProperty("xem_xet_neu_diem_tren") double xemXetAbove) {

        public Decision decide(double score) {
            if (score > chanAbove) {
                return Decision.CHAN;
            }
            if (score > xemXetAbove) {
                return Decision.XEM_XET;
            }
            return Decision.CHO_QUA;
        }
    }
}
