package com.frauddetection.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Open-model load test of {@code POST /check-transaction} at a fixed arrival rate.
 *
 * <p>"Open model": new requests arrive at the target rate whether or not earlier ones have
 * answered, like card payments in real life. (A closed model, "N users each waiting for their
 * answer", slows down together with the system and hides saturation.)
 *
 * <p>Parameters (system properties):
 * <ul>
 *   <li>{@code baseUrl} default http://localhost:8082</li>
 *   <li>{@code rate} target requests per second, default 200</li>
 *   <li>{@code rampSeconds} ramp from 10% to the target rate, default 10</li>
 *   <li>{@code holdSeconds} time at the target rate, default 30</li>
 * </ul>
 * Transactions use random background cards (bg-00001 .. bg-50000, which have a 30-day average),
 * so rules only fire on the realistic share of odd transactions instead of on every request.
 */
public class CheckTransactionSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8082");
    private static final int RATE = Integer.getInteger("rate", 200);
    private static final int RAMP_SECONDS = Integer.getInteger("rampSeconds", 10);
    private static final int HOLD_SECONDS = Integer.getInteger("holdSeconds", 30);

    private static final double[][] CITIES = {
            {21.0285, 105.8542}, {10.8231, 106.6297}, {16.0544, 108.2022}, {20.8449, 106.6881}, {10.0452, 105.7469}
    };
    private static final String[] MERCHANTS = {"SHOPEE", "LAZADA", "GRAB", "CIRCLE_K", "HIGHLANDS", "WINMART", "TGDD", "ATM"};
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** An endless stream of random transaction attributes. */
    private static final Iterator<Map<String, Object>> TRANSACTIONS = Stream.generate(
            (Supplier<Map<String, Object>>) () -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                double[] city = CITIES[rnd.nextInt(CITIES.length)];
                return Map.of(
                        "transactionId", "lt-" + SEQUENCE.incrementAndGet(),
                        "cardId", "bg-%05d".formatted(1 + rnd.nextInt(50_000)),
                        "amount", 1000L * rnd.nextLong(20, 3_000),
                        "merchant", MERCHANTS[rnd.nextInt(MERCHANTS.length)],
                        "lat", city[0] + rnd.nextDouble(-0.03, 0.03),
                        "lon", city[1] + rnd.nextDouble(-0.03, 0.03));
            }).iterator();

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .contentTypeHeader("application/json")
            .acceptHeader("application/json")
            .shareConnections(); // a pool of keep-alive connections, like a real API gateway

    private final ScenarioBuilder checkTransaction = scenario("check-transaction")
            .feed(TRANSACTIONS)
            .exec(http("POST /check-transaction")
                    .post("/check-transaction")
                    .body(StringBody("""
                            {"transactionId":"#{transactionId}","cardId":"#{cardId}","amount":#{amount},\
                            "merchant":"#{merchant}","location":{"lat":#{lat},"lon":#{lon}}}"""))
                    .check(status().is(200))
                    .check(jsonPath("$.decision").exists()));

    {
        setUp(checkTransaction.injectOpen(
                rampUsersPerSec(Math.max(1, RATE / 10)).to(RATE).during(RAMP_SECONDS),
                constantUsersPerSec(RATE).during(HOLD_SECONDS)))
                .protocols(httpProtocol)
                .assertions(global().failedRequests().percent().lt(1.0));
    }
}
