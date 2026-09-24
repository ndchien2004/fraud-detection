package com.frauddetection.common;

import java.time.Duration;
import java.time.Instant;

/**
 * The 5 feature formulas of the spec (section 5), shared by the feature service and the scoring
 * service so both always compute them the same way.
 */
public final class FeatureCalculator {

    public static final Duration COUNT_WINDOW = Duration.ofMinutes(5);
    public static final Duration SUM_WINDOW = Duration.ofHours(1);
    /** Faster than a commercial airliner: nobody can physically travel like this. */
    public static final double MAX_SPEED_KMH = 900;
    /**
     * Distances below this are ignored: two shops in the same city a few seconds apart would
     * otherwise imply an absurd speed (3 km in 1 s = 10,800 km/h).
     */
    public static final double MIN_DISTANCE_KM = 50;

    private static final double EARTH_RADIUS_KM = 6371.0;

    private FeatureCalculator() {
    }

    /**
     * Features of {@code tx}, given the card's state <b>before</b> this transaction.
     *
     * @param previous      state built from the card's earlier transactions (may be null or empty)
     * @param historicalAvg 30-day average amount of the card; 0 when unknown
     */
    public static Features compute(CardState previous, double historicalAvg, Transaction tx) {
        CardState state = previous != null ? previous : CardState.empty();
        Instant at = tx.timestamp();
        return new Features(
                state.countInWindow(at, COUNT_WINDOW) + 1,
                state.sumInWindow(at, SUM_WINDOW) + tx.amount(),
                historicalAvg,
                deviationFromAverage(tx.amount(), historicalAvg),
                isImpossibleTravel(state.lastLocation(), state.lastTimestamp(), tx.location(), at));
    }

    /** (amount - avg) / avg, e.g. 19.0 for an amount 20 times the average; 0 when avg is unknown. */
    public static double deviationFromAverage(long amount, double avg) {
        if (avg <= 0) {
            return 0;
        }
        return (amount - avg) / avg;
    }

    /** True when going from the previous transaction's place to this one would need > 900 km/h. */
    public static boolean isImpossibleTravel(Location previousLocation, Instant previousTime,
                                             Location location, Instant time) {
        if (previousLocation == null || previousTime == null || location == null) {
            return false;
        }
        double distanceKm = haversineKm(previousLocation, location);
        if (distanceKm < MIN_DISTANCE_KM) {
            return false;
        }
        long millis = Duration.between(previousTime, time).toMillis();
        if (millis <= 0) {
            return true; // far apart at the same instant
        }
        return impliedSpeedKmh(distanceKm, millis) > MAX_SPEED_KMH;
    }

    public static double impliedSpeedKmh(double distanceKm, long millis) {
        return distanceKm / (millis / 3_600_000.0);
    }

    /** Great-circle distance between two points on Earth (Haversine formula). */
    public static double haversineKm(Location a, Location b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(h));
    }
}
