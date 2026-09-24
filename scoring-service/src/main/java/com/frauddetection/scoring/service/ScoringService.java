package com.frauddetection.scoring.service;

import com.frauddetection.common.CardState;
import com.frauddetection.common.Decision;
import com.frauddetection.common.FeatureCalculator;
import com.frauddetection.common.Features;
import com.frauddetection.common.ScoreResult;
import com.frauddetection.common.Transaction;
import com.frauddetection.scoring.model.RiskModel;
import com.frauddetection.scoring.rules.RuleEngine;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Scores one transaction:
 * <ol>
 *   <li>read the card's previous state and 30-day average from Redis</li>
 *   <li>compute the 5 features (this transaction included)</li>
 *   <li>apply rules.yaml top to bottom: the first match decides</li>
 *   <li>otherwise ask the model and apply ml_thresholds</li>
 * </ol>
 */
@Service
public class ScoringService {

    private final CardFeatureSource source;
    private final RuleEngine ruleEngine;
    private final RiskModel model;

    public ScoringService(CardFeatureSource source, RuleEngine ruleEngine, RiskModel model) {
        this.source = source;
        this.ruleEngine = ruleEngine;
        this.model = model;
    }

    public ScoreResult score(Transaction tx) {
        CardState previous = source.state(tx.cardId()).orElse(null);
        Features features = FeatureCalculator.compute(previous, source.average(tx.cardId()), tx);

        Optional<RuleEngine.Match> match = ruleEngine.firstMatch(variables(tx, features));
        if (match.isPresent()) {
            return new ScoreResult(match.get().action(), null, match.get().ruleName(), features);
        }

        double score = Math.round(model.score(tx, features) * 10_000) / 10_000.0;
        Decision decision = ruleEngine.thresholds().decide(score);
        return new ScoreResult(decision, score, null, features);
    }

    public String modelName() {
        return model.name();
    }

    /** The names a condition in rules.yaml can use (see {@link RuleEngine#SAMPLE_VARIABLES}). */
    static Map<String, Object> variables(Transaction tx, Features f) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", tx.amount());
        vars.put("merchant", tx.merchant());
        vars.put("cardId", tx.cardId());
        vars.put("so_giao_dich_5_phut", f.soGiaoDich5Phut());
        vars.put("tong_tien_1_gio", f.tongTien1Gio());
        vars.put("trung_binh_lich_su", f.trungBinhLichSu());
        vars.put("lech_so_voi_trung_binh", f.lechSoVoiTrungBinh());
        vars.put("khoang_cach_bat_thuong", f.khoangCachBatThuong());
        return vars;
    }
}
