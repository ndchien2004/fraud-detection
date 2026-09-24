package com.frauddetection.scoring.model;

import com.frauddetection.common.Features;
import com.frauddetection.common.Transaction;
import org.springframework.stereotype.Component;

/**
 * Hand-written stand-in for the ML model: a weighted sum of normalised features.
 * <ul>
 *   <li>deviation: 10x above the average or more counts fully (weight 0.6)</li>
 *   <li>velocity: 6 or more transactions in 5 minutes counts fully (weight 0.25)</li>
 *   <li>hourly spend: 20x the average or more counts fully (weight 0.15)</li>
 * </ul>
 * So an amount 20x the average scores about 0.75 (XEM_XET) and a normal purchase about 0.
 */
@Component
public class WeightedScoreModel implements RiskModel {

    static final double DEVIATION_WEIGHT = 0.6;
    static final double VELOCITY_WEIGHT = 0.25;
    static final double HOURLY_SPEND_WEIGHT = 0.15;

    @Override
    public double score(Transaction tx, Features f) {
        double deviation = clamp(f.lechSoVoiTrungBinh() / 10);
        double velocity = clamp((f.soGiaoDich5Phut() - 1) / 5.0);
        double hourlySpend = f.trungBinhLichSu() > 0 ? clamp(f.tongTien1Gio() / (f.trungBinhLichSu() * 20)) : 0;
        return DEVIATION_WEIGHT * deviation + VELOCITY_WEIGHT * velocity + HOURLY_SPEND_WEIGHT * hourlySpend;
    }

    @Override
    public String name() {
        return "weighted-score";
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
