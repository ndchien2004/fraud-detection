package com.frauddetection.scoring.service;

import com.frauddetection.common.CardState;
import com.frauddetection.common.CardStateRedisCodec;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class RedisCardFeatureSource implements CardFeatureSource {

    private final StringRedisTemplate redis;

    RedisCardFeatureSource(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<CardState> state(String cardId) {
        return CardStateRedisCodec.fromHash(redis.opsForHash().entries(CardStateRedisCodec.stateKey(cardId)));
    }

    @Override
    public double average(String cardId) {
        return CardStateRedisCodec.parseAverage(redis.opsForValue().get(CardStateRedisCodec.averageKey(cardId)));
    }
}
