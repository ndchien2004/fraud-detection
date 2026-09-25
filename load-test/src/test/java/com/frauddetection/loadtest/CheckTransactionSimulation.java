package com.frauddetection.loadtest;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import com.frauddetection.common.CardProfile;
import com.frauddetection.common.CardProfiles;
import com.frauddetection.common.Location;
import com.frauddetection.common.Merchant;
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
 * Transactions are ordinary purchases of random background cards (bg-00001 .. bg-50000, which have
 * a 30-day average): in the card's home city, around its typical amount, exactly like the
 * simulator's auto mode. So almost every request goes the full way through the ML model, and the
 * test leaves no fake history behind (a first version used random cities: cards then looked like
 * they had "flown" across the country, and auto mode blocked 17% of transactions afterwards).
 */
public class CheckTransactionSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8082");
    private static final int RATE = Integer.getInteger("rate", 200);
    private static final int RAMP_SECONDS = Integer.getInteger("rampSeconds", 10);
    private static final int HOLD_SECONDS = Integer.getInteger("holdSeconds", 30);

    private static final Merchant[] MERCHANTS = Merchant.values();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** An endless stream of random transaction attributes. */
    private static final Iterator<Map<String, Object>> TRANSACTIONS = Stream.generate(
            (Supplier<Map<String, Object>>) () -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                CardProfile card = CardProfiles.background(1 + rnd.nextInt(CardProfiles.BACKGROUND_COUNT));
                Location home = card.homeCity().location();
                long amount = Math.max(10_000, Math.round(card.typicalAmount() * Math.exp(rnd.nextGaussian() * 0.4) / 1000) * 1000);
                return Map.of(
                        "transactionId", "lt-" + SEQUENCE.incrementAndGet(),
                        "cardId", card.cardId(),
                        "amount", amount,
                        "merchant", MERCHANTS[rnd.nextInt(MERCHANTS.length)].name(),
                        "lat", home.lat() + rnd.nextDouble(-0.03, 0.03),
                        "lon", home.lon() + rnd.nextDouble(-0.03, 0.03));
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
