package com.frauddetection.feature.redis;

import com.frauddetection.common.CardState;
import com.frauddetection.common.CardStateRedisCodec;
import com.frauddetection.feature.stream.CardStateSink;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** Writes card states and 30-day averages to Redis, using the layout of {@link CardStateRedisCodec}. */
@Repository
public class RedisCardStateRepository implements CardStateSink {

    private static final Logger log = LoggerFactory.getLogger(RedisCardStateRepository.class);
    /** Idle cards disappear from Redis once their 1-hour window is surely empty. */
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;

    public RedisCardStateRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void write(String cardId, CardState state) {
        // Do not let a Redis outage kill the stream thread: the full state is rewritten on the
        // card's next transaction, so Redis catches up by itself once it is back.
        try {
            String key = CardStateRedisCodec.stateKey(cardId);
            redis.opsForHash().putAll(key, CardStateRedisCodec.toHash(state));
            redis.expire(key, TTL);
        } catch (RuntimeException e) {
            log.warn("Could not write state of {} to Redis: {}", cardId, e.getMessage());
        }
    }

    public Optional<CardState> find(String cardId) {
        return CardStateRedisCodec.fromHash(redis.opsForHash().entries(CardStateRedisCodec.stateKey(cardId)));
    }

    /** One MSET for many cards. No TTL: averages are refreshed by the (simulated) offline job. */
    public void saveAverages(Map<String, Double> averagesByCardId) {
        if (averagesByCardId.isEmpty()) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        averagesByCardId.forEach((cardId, avg) ->
                values.put(CardStateRedisCodec.averageKey(cardId), String.valueOf(avg)));
        redis.opsForValue().multiSet(values);
    }

    /** 30-day average amount, or 0 when unknown (e.g. a card that is not simulated). */
    public double findAverage(String cardId) {
        return CardStateRedisCodec.parseAverage(redis.opsForValue().get(CardStateRedisCodec.averageKey(cardId)));
    }
}
