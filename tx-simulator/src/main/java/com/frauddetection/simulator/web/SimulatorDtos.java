package com.frauddetection.simulator.web;

import com.frauddetection.common.CardProfiles;
import com.frauddetection.common.City;
import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.HistoricalAverage;
import com.frauddetection.common.Location;
import com.frauddetection.common.Merchant;
import com.frauddetection.common.Transaction;
import com.frauddetection.simulator.service.AutoModeService;
import com.frauddetection.simulator.service.SimulatorStats;
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

    public record ManualTransactionResponse(Transaction transaction, DecisionResult result) {
    }

    /** @param ratePerSecond optional; keeps the previous rate when omitted */
    public record AutoModeRequest(boolean enabled, @Min(1) @Max(500) Integer ratePerSecond) {
    }

    public record StatsResponse(SimulatorStats.Snapshot totals, AutoModeService.Status autoMode) {
    }

    public record Option(String code, String name) {
    }

    public record CardOption(String cardId, String homeCity, String homeCityName, long typicalAmount,
                             double historicalAverage) {
    }

    public record CityOption(String code, String name, Location location) {
    }

    public record CatalogResponse(List<CardOption> cards, List<Option> merchants, List<CityOption> cities) {

        static CatalogResponse create() {
            return new CatalogResponse(
                    CardProfiles.DEMO.stream().map(c -> new CardOption(c.cardId(), c.homeCity().name(),
                            c.homeCity().displayName(), c.typicalAmount(), HistoricalAverage.of(c))).toList(),
                    Arrays.stream(Merchant.values()).map(m -> new Option(m.name(), m.displayName())).toList(),
                    Arrays.stream(City.values()).map(c -> new CityOption(c.name(), c.displayName(), c.location())).toList());
        }
    }
}
