package com.frauddetection.scoring.service;

import com.frauddetection.common.CardState;
import java.util.Optional;

/** Where the scoring service reads what the feature service computed (Redis in production). */
public interface CardFeatureSource {

    Optional<CardState> state(String cardId);

    /** 30-day average amount, or 0 when unknown. */
    double average(String cardId);
}
