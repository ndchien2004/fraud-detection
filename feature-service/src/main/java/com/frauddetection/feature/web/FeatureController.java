package com.frauddetection.feature.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.common.CardState;
import com.frauddetection.common.Location;
import com.frauddetection.feature.redis.RedisCardStateRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Debug endpoint: what the feature service currently knows about a card (read back from Redis). */
@RestController
public class FeatureController {

    private final RedisCardStateRepository repository;

    public FeatureController(RedisCardStateRepository repository) {
        this.repository = repository;
    }

    /** Features as of the card's last transaction (that transaction included). */
    @GetMapping("/features/{cardId}")
    public FeatureSnapshot features(@PathVariable String cardId) {
        CardState state = repository.find(cardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No state for " + cardId));
        Instant at = state.lastTimestamp();
        return new FeatureSnapshot(cardId,
                state.countInWindow(at, Duration.ofMinutes(5)),
                state.sumInWindow(at, Duration.ofHours(1)),
                at, state.lastLocation(), state.buckets().size());
    }

    public record FeatureSnapshot(
            String cardId,
            @JsonProperty("so_giao_dich_5_phut") int soGiaoDich5Phut,
            @JsonProperty("tong_tien_1_gio") long tongTien1Gio,
            Instant lastTimestamp,
            Location lastLocation,
            int bucketCount) {
    }
}
