package com.frauddetection.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis layout shared by the feature service (writer) and the scoring service (reader):
 * <ul>
 *   <li>{@code card:{cardId}:state} - hash with fields {@code buckets} (JSON), {@code lastLat},
 *       {@code lastLon}, {@code lastTs}</li>
 *   <li>{@code card:{cardId}:avg} - 30-day average amount as a plain string</li>
 * </ul>
 */
public final class CardStateRedisCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<CardState.Bucket>> BUCKETS_TYPE = new TypeReference<>() {
    };

    private CardStateRedisCodec() {
    }

    public static String stateKey(String cardId) {
        return "card:" + cardId + ":state";
    }

    public static String averageKey(String cardId) {
        return "card:" + cardId + ":avg";
    }

    public static Map<String, String> toHash(CardState state) {
        try {
            return Map.of(
                    "buckets", MAPPER.writeValueAsString(state.buckets()),
                    "lastLat", String.valueOf(state.lastLocation().lat()),
                    "lastLon", String.valueOf(state.lastLocation().lon()),
                    "lastTs", state.lastTimestamp().toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize card state", e);
        }
    }

    /** @param hash the raw Redis hash; empty when the card has no state yet */
    public static Optional<CardState> fromHash(Map<?, ?> hash) {
        if (hash == null || hash.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<CardState.Bucket> buckets = MAPPER.readValue((String) hash.get("buckets"), BUCKETS_TYPE);
            Location last = new Location(
                    Double.parseDouble((String) hash.get("lastLat")),
                    Double.parseDouble((String) hash.get("lastLon")));
            return Optional.of(new CardState(buckets, last, Instant.parse((String) hash.get("lastTs"))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupted card state in Redis", e);
        }
    }

    /** @param value the raw Redis string; null when unknown */
    public static double parseAverage(String value) {
        return value != null ? Double.parseDouble(value) : 0;
    }
}
