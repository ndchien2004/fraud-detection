package com.frauddetection.feature.stream;

import com.frauddetection.common.CardState;

/** Where the topology publishes each updated card state (Redis in production, a map in tests). */
@FunctionalInterface
public interface CardStateSink {

    void write(String cardId, CardState state);
}
