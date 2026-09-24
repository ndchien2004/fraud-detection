package com.frauddetection.feature.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.frauddetection.common.CardState;
import com.frauddetection.common.City;
import com.frauddetection.common.FeatureCalculator;
import com.frauddetection.common.Features;
import com.frauddetection.common.Location;
import com.frauddetection.common.Transaction;
import com.frauddetection.feature.redis.RedisCardStateRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Debug endpoints: what the feature service knows about a card (read back from Redis). */
@RestController
public class FeatureController {

    private final RedisCardStateRepository repository;
    private final Clock clock;

    public FeatureController(RedisCardStateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Window totals as of the card's last transaction (that transaction included). */
    @GetMapping("/features/{cardId}")
    public FeatureSnapshot features(@PathVariable String cardId) {
        CardState state = repository.find(cardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No state for " + cardId));
        Instant at = state.lastTimestamp();
        return new FeatureSnapshot(cardId,
                state.countInWindow(at, FeatureCalculator.COUNT_WINDOW),
                state.sumInWindow(at, FeatureCalculator.SUM_WINDOW),
                repository.findAverage(cardId),
                at, state.lastLocation(), state.buckets().size());
    }

    /**
     * "What if this card paid now?": the 5 features of a hypothetical next transaction, computed
     * exactly like the scoring service will do. Nothing is stored.
     */
    @PostMapping("/features/preview")
    public Features preview(@Valid @RequestBody PreviewRequest request) {
        City city = City.fromCode(request.city())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown city: " + request.city()));
        Instant at = request.timestamp() != null ? request.timestamp() : Instant.now(clock);
        Transaction tx = new Transaction("preview", request.cardId(), request.amount(), "PREVIEW", city.location(), at);
        CardState state = repository.find(request.cardId()).orElse(null);
        return FeatureCalculator.compute(state, repository.findAverage(request.cardId()), tx);
    }

    public record PreviewRequest(
            @NotBlank String cardId,
            @NotNull @Positive Long amount,
            @NotBlank String city,
            Instant timestamp) {
    }

    public record FeatureSnapshot(
            String cardId,
            @JsonProperty("so_giao_dich_5_phut") int soGiaoDich5Phut,
            @JsonProperty("tong_tien_1_gio") long tongTien1Gio,
            @JsonProperty("trung_binh_lich_su") double trungBinhLichSu,
            Instant lastTimestamp,
            Location lastLocation,
            int bucketCount) {
    }
}
