package com.frauddetection.simulator.web;

import com.frauddetection.common.CardProfile;
import com.frauddetection.common.CardProfiles;
import com.frauddetection.common.City;
import com.frauddetection.common.Merchant;
import com.frauddetection.common.Transaction;
import com.frauddetection.simulator.service.AutoModeService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Request/response bodies of {@link SimulatorController}. */
public final class SimulatorDtos {

    private SimulatorDtos() {
    }

    /** @param timestamp optional; defaults to now */
    public record ManualTransactionRequest(
            @NotBlank String cardId,
            @NotNull @Positive Long amount,
            @NotBlank String merchant,
            @NotBlank String city,
            Instant timestamp) {
    }

    public record ManualTransactionResponse(Transaction transaction, String topic, int partition, long offset) {
    }

    /** @param ratePerSecond optional; keeps the previous rate when omitted */
    public record AutoModeRequest(boolean enabled, @Min(1) @Max(500) Integer ratePerSecond) {
    }

    public record StatsResponse(long totalSent, long totalFailed, AutoModeService.Status autoMode) {
    }

    public record Option(String code, String name) {
    }

    public record CatalogResponse(List<CardProfile> cards, List<Option> merchants, List<Option> cities) {

        static CatalogResponse create() {
            return new CatalogResponse(
                    CardProfiles.ALL,
                    Arrays.stream(Merchant.values()).map(m -> new Option(m.name(), m.displayName())).toList(),
                    Arrays.stream(City.values()).map(c -> new Option(c.name(), c.displayName())).toList());
        }
    }
}
