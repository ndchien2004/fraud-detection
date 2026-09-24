package com.frauddetection.scoring.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.frauddetection.common.Decision;
import com.frauddetection.scoring.rules.RulesConfig.MlThresholds;
import com.frauddetection.scoring.rules.RulesConfig.RuleDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * Loads rules.yaml, compiles each condition once, and evaluates them top to bottom.
 *
 * <p>Conditions are Spring Expression Language evaluated in a {@link SimpleEvaluationContext}:
 * they can only read the given variables, never call methods or construct objects, so editing
 * rules.yaml cannot run arbitrary code.
 *
 * <p>Thread-safe: requests read an immutable snapshot, and a reload swaps the whole snapshot at once.
 */
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    /** Variables a condition may use, with sample values used to validate conditions on load. */
    public static final Map<String, Object> SAMPLE_VARIABLES = Map.of(
            "amount", 0L,
            "merchant", "",
            "cardId", "",
            "so_giao_dich_5_phut", 0,
            "tong_tien_1_gio", 0L,
            "trung_binh_lich_su", 0.0,
            "lech_so_voi_trung_binh", 0.0,
            "khoang_cach_bat_thuong", false);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final EvaluationContext CONTEXT =
            SimpleEvaluationContext.forPropertyAccessors(new MapAccessor()).build();

    private final Path file;
    private volatile Snapshot snapshot;

    /** Loads the file immediately; an invalid file at startup is a fatal error. */
    public RuleEngine(Path file) {
        this.file = file;
        this.snapshot = load(file);
        log.info("Loaded {} rules from {}", snapshot.rules().size(), file.toAbsolutePath().normalize());
    }

    public record CompiledRule(String name, String condition, Decision action, Expression expression) {
    }

    public record Snapshot(List<CompiledRule> rules, MlThresholds thresholds, FileTime lastModified, Instant loadedAt) {
    }

    public record Match(String ruleName, Decision action) {
    }

    /** First rule whose condition is true, or empty when none matches. */
    public Optional<Match> firstMatch(Map<String, Object> variables) {
        for (CompiledRule rule : snapshot.rules()) {
            Boolean matched = rule.expression().getValue(CONTEXT, variables, Boolean.class);
            if (Boolean.TRUE.equals(matched)) {
                return Optional.of(new Match(rule.name(), rule.action()));
            }
        }
        return Optional.empty();
    }

    public MlThresholds thresholds() {
        return snapshot.thresholds();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** Reloads only when the file was modified since the last load. */
    public boolean reloadIfChanged() {
        try {
            if (Files.getLastModifiedTime(file).equals(snapshot.lastModified())) {
                return false;
            }
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", file, e.getMessage());
            return false;
        }
        return reload();
    }

    /**
     * Reloads the file. On any error (bad YAML, bad condition, unknown action) the previous rules
     * stay active: a typo in rules.yaml must never take the fraud checks down.
     */
    public boolean reload() {
        try {
            snapshot = load(file);
            log.info("Reloaded {} rules from {}", snapshot.rules().size(), file);
            return true;
        } catch (RuntimeException e) {
            log.error("Invalid {} - keeping the previous rules: {}", file, e.getMessage());
            return false;
        }
    }

    private static Snapshot load(Path file) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(file);
            RulesConfig config = YAML.readValue(file.toFile(), RulesConfig.class);
            if (config.mlThresholds() == null) {
                throw new IllegalArgumentException("ml_thresholds is missing");
            }
            List<CompiledRule> compiled = new ArrayList<>();
            for (RuleDefinition def : config.rules() != null ? config.rules() : List.<RuleDefinition>of()) {
                compiled.add(compile(def));
            }
            return new Snapshot(List.copyOf(compiled), config.mlThresholds(), lastModified, Instant.now());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read rules file " + file, e);
        }
    }

    private static CompiledRule compile(RuleDefinition def) {
        if (def.name() == null || def.condition() == null || def.action() == null) {
            throw new IllegalArgumentException("Rule needs name, condition and action: " + def);
        }
        Expression expression = PARSER.parseExpression(def.condition());
        // evaluate once with sample values: catches unknown variable names and non-boolean results now
        Object sample = expression.getValue(CONTEXT, SAMPLE_VARIABLES);
        if (!(sample instanceof Boolean)) {
            throw new IllegalArgumentException("Condition of rule '" + def.name() + "' is not a boolean: " + def.condition());
        }
        return new CompiledRule(def.name(), def.condition(), def.action(), expression);
    }
}
