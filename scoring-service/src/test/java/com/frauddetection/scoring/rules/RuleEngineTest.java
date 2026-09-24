package com.frauddetection.scoring.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.frauddetection.common.Decision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleEngineTest {

    private static final String RULES = """
            rules:
              - name: "qua_nhieu_giao_dich"
                condition: "so_giao_dich_5_phut > %d"
                action: "CHAN"
              - name: "di_chuyen_bat_kha_thi"
                condition: "khoang_cach_bat_thuong == true"
                action: "CHAN"
              - name: "chi_tieu_qua_cao_tuyet_doi"
                condition: "amount > 50000000"
                action: "XEM_XET"
            ml_thresholds:
              chan_neu_diem_tren: 0.8
              xem_xet_neu_diem_tren: 0.4
            """;

    @TempDir
    Path dir;

    private Path writeRules(String content) throws IOException {
        Path file = dir.resolve("rules.yaml");
        Files.writeString(file, content);
        return file;
    }

    private static Map<String, Object> vars(int count, boolean travel, long amount) {
        Map<String, Object> vars = new HashMap<>(RuleEngine.SAMPLE_VARIABLES);
        vars.put("so_giao_dich_5_phut", count);
        vars.put("khoang_cach_bat_thuong", travel);
        vars.put("amount", amount);
        return vars;
    }

    @Test
    void firstMatchingRuleWinsInFileOrder() throws IOException {
        RuleEngine engine = new RuleEngine(writeRules(RULES.formatted(5)));

        // both rule 1 and rule 3 match: rule 1 comes first
        assertThat(engine.firstMatch(vars(6, false, 60_000_000)))
                .contains(new RuleEngine.Match("qua_nhieu_giao_dich", Decision.CHAN));
        assertThat(engine.firstMatch(vars(1, true, 100)))
                .contains(new RuleEngine.Match("di_chuyen_bat_kha_thi", Decision.CHAN));
        assertThat(engine.firstMatch(vars(1, false, 60_000_000)))
                .contains(new RuleEngine.Match("chi_tieu_qua_cao_tuyet_doi", Decision.XEM_XET));
        assertThat(engine.firstMatch(vars(5, false, 100))).isEmpty();
    }

    @Test
    void thresholdsDecideWhenNoRuleMatches() throws IOException {
        RuleEngine engine = new RuleEngine(writeRules(RULES.formatted(5)));

        assertThat(engine.thresholds().decide(0.9)).isEqualTo(Decision.CHAN);
        assertThat(engine.thresholds().decide(0.63)).isEqualTo(Decision.XEM_XET);
        assertThat(engine.thresholds().decide(0.4)).isEqualTo(Decision.CHO_QUA);
    }

    @Test
    void editedFileIsPickedUpWithoutRestart() throws IOException {
        Path file = writeRules(RULES.formatted(5));
        RuleEngine engine = new RuleEngine(file);
        assertThat(engine.firstMatch(vars(4, false, 100))).isEmpty();

        Files.writeString(file, RULES.formatted(3));
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(10)));

        assertThat(engine.reloadIfChanged()).isTrue();
        assertThat(engine.firstMatch(vars(4, false, 100))).isPresent();
        assertThat(engine.reloadIfChanged()).isFalse(); // unchanged since
    }

    @Test
    void invalidEditKeepsThePreviousRules() throws IOException {
        Path file = writeRules(RULES.formatted(5));
        RuleEngine engine = new RuleEngine(file);

        Files.writeString(file, RULES.formatted(5).replace("so_giao_dich_5_phut", "so_giao_dich_typo"));
        assertThat(engine.reload()).isFalse();

        assertThat(engine.snapshot().rules()).hasSize(3);
        assertThat(engine.firstMatch(vars(6, false, 100))).isPresent();
    }

    @Test
    void invalidFileAtStartupFailsFast() throws IOException {
        Path file = writeRules("rules:\n  - name: x\n    condition: \"amount +\"\n    action: CHAN\nml_thresholds:\n  chan_neu_diem_tren: 0.8\n  xem_xet_neu_diem_tren: 0.4\n");
        assertThatThrownBy(() -> new RuleEngine(file)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void conditionsCannotCallJavaCode() throws IOException {
        Path file = writeRules("""
                rules:
                  - name: "evil"
                    condition: "T(java.lang.Runtime).getRuntime().exec('calc') != null"
                    action: "CHAN"
                ml_thresholds:
                  chan_neu_diem_tren: 0.8
                  xem_xet_neu_diem_tren: 0.4
                """);
        assertThatThrownBy(() -> new RuleEngine(file)).isInstanceOf(RuntimeException.class);
    }
}
