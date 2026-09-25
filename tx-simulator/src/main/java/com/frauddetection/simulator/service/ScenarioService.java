package com.frauddetection.simulator.service;

import com.frauddetection.common.CardProfile;
import com.frauddetection.common.CardProfiles;
import com.frauddetection.common.City;
import com.frauddetection.common.Decision;
import com.frauddetection.common.DecisionResult;
import com.frauddetection.common.HistoricalAverage;
import com.frauddetection.common.Merchant;
import com.frauddetection.common.Transaction;
import com.frauddetection.simulator.service.SimulationService.DecisionApiUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * The 3 preset fraud scenarios. Each run sends a short series of transactions on one demo card,
 * one step at a time, and reports every step on the STOMP topic {@value #TOPIC} so the UI can show
 * exactly at which transaction the system starts to react.
 */
@Service
public class ScenarioService {

    public static final String TOPIC = "/topic/scenario";

    public enum Scenario {
        RAPID_FIRE("rapid-fire", "Quẹt dồn dập", "Từ giao dịch thứ 6 bị CHAN (qua_nhieu_giao_dich)"),
        IMPOSSIBLE_TRAVEL("impossible-travel", "Impossible travel", "Giao dịch thứ 2 bị CHAN (di_chuyen_bat_kha_thi)"),
        UNUSUAL_AMOUNT("unusual-amount", "Chi tiêu bất thường", "Bị XEM_XET hoặc CHAN theo điểm ML");

        private final String slug;
        private final String title;
        private final String expectation;

        Scenario(String slug, String title, String expectation) {
            this.slug = slug;
            this.title = title;
            this.expectation = expectation;
        }

        public String slug() {
            return slug;
        }

        public static Optional<Scenario> fromSlug(String slug) {
            return Arrays.stream(values()).filter(s -> s.slug.equals(slug)).findFirst();
        }
    }

    public record Run(String runId, String scenario, String title, String expectation, String cardId, int totalSteps) {
    }

    public record StepResult(int step, String description, Transaction transaction, DecisionResult result) {
    }

    /** @param firstDetectedStep first step that was not CHO_QUA, or null */
    public record Report(Run run, List<StepResult> steps, Integer firstDetectedStep, String summary) {
    }

    /** One message on the topic: progress ("sending 3/10"), a step result, or the final summary. */
    public record Event(String runId, String scenario, int step, int totalSteps, String message,
                        StepResult stepResult, boolean finished, String summary) {
    }

    private record Step(String description, Function<List<StepResult>, Transaction> transaction) {
    }

    private record Plan(Run run, List<Step> steps) {
    }

    private static final int RAPID_FIRE_COUNT = 10;
    private static final Duration TRAVEL_GAP = Duration.ofMinutes(2);

    private final TransactionGenerator generator;
    private final SimulationService simulation;
    private final CardClock cardClock;
    private final SimpMessagingTemplate messaging;
    private final long stepDelayMs;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ScenarioService(TransactionGenerator generator, SimulationService simulation, CardClock cardClock,
                           SimpMessagingTemplate messaging, @Value("${app.scenario.step-delay-ms}") long stepDelayMs) {
        this.generator = generator;
        this.simulation = simulation;
        this.cardClock = cardClock;
        this.messaging = messaging;
        this.stepDelayMs = stepDelayMs;
    }

    /** Starts the scenario in the background; progress goes to the WebSocket topic. */
    public Run start(Scenario scenario) {
        Plan plan = plan(scenario);
        executor.submit(() -> execute(plan));
        return plan.run();
    }

    /** Runs the scenario and returns only when it is finished (handy from curl or tests). */
    public Report runAndWait(Scenario scenario) {
        return execute(plan(scenario));
    }

    private Plan plan(Scenario scenario) {
        List<String> demoIds = CardProfiles.DEMO.stream().map(CardProfile::cardId).toList();
        return switch (scenario) {
            case RAPID_FIRE -> rapidFire(card(cardClock.leastRecentlyUsed(demoIds)));
            case IMPOSSIBLE_TRAVEL -> impossibleTravel(card(cardClock.leastRecentlyUsed(CardProfiles.DEMO.stream()
                    .filter(c -> c.homeCity() == City.HA_NOI).map(CardProfile::cardId).toList())));
            case UNUSUAL_AMOUNT -> unusualAmount(card(cardClock.leastRecentlyUsed(demoIds)));
        };
    }

    /** 10 ordinary purchases on the same card, one per second. */
    private Plan rapidFire(CardProfile card) {
        List<Step> steps = new ArrayList<>();
        for (int i = 1; i <= RAPID_FIRE_COUNT; i++) {
            steps.add(new Step("Quẹt thẻ lần %d tại %s".formatted(i, card.homeCity().displayName()), done -> {
                long amount = TransactionGenerator.roundToThousand(
                        card.typicalAmount() * ThreadLocalRandom.current().nextDouble(0.8, 1.2));
                return generator.manual(card.cardId(), amount, Merchant.CIRCLE_K, card.homeCity(), null);
            }));
        }
        return new Plan(run(Scenario.RAPID_FIRE, card, steps.size()), steps);
    }

    /** Hà Nội, then TP.HCM (~1,140 km) stamped 2 minutes later. */
    private Plan impossibleTravel(CardProfile card) {
        List<Step> steps = List.of(
                new Step("Thanh toán tại Hà Nội", done ->
                        generator.manual(card.cardId(), card.typicalAmount(), Merchant.GRAB, City.HA_NOI, null)),
                new Step("2 phút sau, thanh toán tại TP.HCM (cách ~1.140 km)", done ->
                        generator.manual(card.cardId(), card.typicalAmount(), Merchant.GRAB, City.HO_CHI_MINH,
                                done.get(0).transaction().timestamp().plus(TRAVEL_GAP))));
        return new Plan(run(Scenario.IMPOSSIBLE_TRAVEL, card, steps.size()), steps);
    }

    /** One purchase of 20 times the card's 30-day average. */
    private Plan unusualAmount(CardProfile card) {
        long amount = TransactionGenerator.roundToThousand(20 * HistoricalAverage.of(card));
        List<Step> steps = List.of(new Step("Mua hàng %,d đ (gấp 20 lần trung bình lịch sử)".formatted(amount), done ->
                generator.manual(card.cardId(), amount, Merchant.TGDD, card.homeCity(), null)));
        return new Plan(run(Scenario.UNUSUAL_AMOUNT, card, steps.size()), steps);
    }

    private Report execute(Plan plan) {
        Run run = plan.run();
        List<StepResult> done = new ArrayList<>();
        int total = plan.steps().size();
        for (int i = 0; i < total; i++) {
            Step step = plan.steps().get(i);
            if (i > 0 && !pause()) {
                break;
            }
            emit(run, i + 1, "Đang gửi giao dịch %d/%d: %s...".formatted(i + 1, total, step.description()), null);
            Transaction tx = step.transaction().apply(done);
            DecisionResult result;
            try {
                result = simulation.submit(tx);
            } catch (DecisionApiUnavailableException e) {
                String error = "Lỗi: không gọi được decision-api (" + e.getCause().getMessage() + ")";
                finish(run, i + 1, error);
                return new Report(run, done, null, error);
            }
            StepResult stepResult = new StepResult(i + 1, step.description(), tx, result);
            done.add(stepResult);
            emit(run, i + 1, "Giao dịch %d/%d: %s".formatted(i + 1, total, describe(result)), stepResult);
        }
        Integer firstDetected = done.stream()
                .filter(s -> s.result().decision() != Decision.CHO_QUA)
                .map(StepResult::step)
                .findFirst().orElse(null);
        String summary = firstDetected == null
                ? "Không giao dịch nào bị chặn hay đưa vào xem xét."
                : "Phát hiện bất thường từ giao dịch thứ %d/%d: %s".formatted(
                        firstDetected, total, describe(done.get(firstDetected - 1).result()));
        finish(run, total, summary);
        return new Report(run, done, firstDetected, summary);
    }

    static String describe(DecisionResult r) {
        if (r.triggeredRule() != null) {
            return r.decision() + " (rule " + r.triggeredRule() + ")";
        }
        return r.riskScore() != null
                ? "%s (điểm ML %.2f)".formatted(r.decision(), r.riskScore())
                : r.decision().toString();
    }

    private static Run run(Scenario scenario, CardProfile card, int totalSteps) {
        return new Run(UUID.randomUUID().toString(), scenario.slug, scenario.title, scenario.expectation,
                card.cardId(), totalSteps);
    }

    private static CardProfile card(String cardId) {
        return CardProfiles.find(cardId).orElseThrow();
    }

    private boolean pause() {
        try {
            Thread.sleep(stepDelayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void emit(Run run, int step, String message, StepResult result) {
        messaging.convertAndSend(TOPIC, new Event(run.runId(), run.scenario(), step, run.totalSteps(), message,
                result, false, null));
    }

    private void finish(Run run, int step, String summary) {
        messaging.convertAndSend(TOPIC, new Event(run.runId(), run.scenario(), step, run.totalSteps(), summary,
                null, true, summary));
    }
}
