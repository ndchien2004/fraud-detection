package com.frauddetection.feature.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.common.CardState;
import com.frauddetection.common.Location;
import com.frauddetection.feature.stream.CardStateSink;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Stores each card's state as a Redis hash {@code card:{cardId}:state} with fields
 * {@code buckets} (JSON), {@code lastLat}, {@code lastLon}, {@code lastTs}, and the 30-day
 * average as a plain string {@code card:{cardId}:avg}.
 */
@Repository
public class RedisCardStateRepository implements CardStateSink {

    private static final Logger log = LoggerFactory.getLogger(RedisCardStateRepository.class);
    private static final TypeReference<List<CardState.Bucket>> BUCKETS_TYPE = new TypeReference<>() {
    };
    /** Idle cards disappear from Redis once their 1-hour window is surely empty. */
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisCardStateRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public static String key(String cardId) {
        return "card:" + cardId + ":state";
    }

    public static String averageKey(String cardId) {
        return "card:" + cardId + ":avg";
    }

    /** No TTL: the average is refreshed by the (simulated) offline job, not by traffic. */
    public void saveAverage(String cardId, double average) {
        redis.opsForValue().set(averageKey(cardId), String.valueOf(average));
    }

    /** 30-day average amount, or 0 when unknown (e.g. a card that is not simulated). */
    public double findAverage(String cardId) {
        String value = redis.opsForValue().get(averageKey(cardId));
        return value != null ? Double.parseDouble(value) : 0;
    }

    @Override
    public void write(String cardId, CardState state) {
        // Do not let a Redis outage kill the stream thread: the full state is rewritten on the
        // card's next transaction, so Redis catches up by itself once it is back.
        try {
            String key = key(cardId);
            redis.opsForHash().putAll(key, Map.of(
                    "buckets", objectMapper.writeValueAsString(state.buckets()),
                    "lastLat", String.valueOf(state.lastLocation().lat()),
                    "lastLon", String.valueOf(state.lastLocation().lon()),
                    "lastTs", state.lastTimestamp().toString()));
            redis.expire(key, TTL);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Could not write state of {} to Redis: {}", cardId, e.getMessage());
        }
    }

    public Optional<CardState> find(String cardId) {
        Map<Object, Object> hash = redis.opsForHash().entries(key(cardId));
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<CardState.Bucket> buckets = objectMapper.readValue((String) hash.get("buckets"), BUCKETS_TYPE);
            Location last = new Location(
                    Double.parseDouble((String) hash.get("lastLat")),
                    Double.parseDouble((String) hash.get("lastLon")));
            return Optional.of(new CardState(buckets, last, Instant.parse((String) hash.get("lastTs"))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted state for " + cardId, e);
        }
    }
}
