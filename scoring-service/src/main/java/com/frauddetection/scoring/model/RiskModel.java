package com.frauddetection.scoring.model;

import com.frauddetection.common.Features;
import com.frauddetection.common.Transaction;

/** Scores a transaction that no rule caught. Phase 5 adds an ONNX implementation. */
public interface RiskModel {

    /** Probability-like fraud score in [0, 1]. */
    double score(Transaction tx, Features features);

    String name();
}
