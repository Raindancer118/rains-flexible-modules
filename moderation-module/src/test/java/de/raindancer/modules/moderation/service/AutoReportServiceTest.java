package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.reports.AutoReports;
import de.raindancer.modules.moderation.AntiCheatSettings;
import de.raindancer.modules.moderation.model.CheckType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AutoReportServiceTest {

    private final UUID cheater = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final List<String> filed = new ArrayList<>();
    private AutoReports.Result answer = AutoReports.Result.FILED;
    private AutoReportService service;

    @BeforeEach
    void setUp() {
        AutoReports.provide((subject, name, kind, text) -> {
            filed.add(subject + "|" + name + "|" + kind + "|" + text);
            return answer;
        });
        service = new AutoReportService(clock::get);
        service.settings(AntiCheatSettings.DEFAULTS);
    }

    @AfterEach
    void tearDown() {
        AutoReports.clear();
    }

    /** One flag a second of one check, with the level a run of them has built up. */
    private void flyFor(int seconds, int ping, double tps) {
        for (int i = 0; i < seconds; i++) {
            clock.addAndGet(1000);
            service.flagged(cheater, "Cheater", CheckType.GRIM_MOVEMENT, 1, "Simulation: hovering (dy 0.0000)", 5 + i, ping, tps, "world 3147 79 100");
        }
    }

    @Test
    @DisplayName("a minute of steady flying is reported once, with the evidence in the text")
    void reportsOnce() {
        flyFor(60, 48, 19.8);
        assertThat(filed).hasSize(1);
        assertThat(filed.get(0)).startsWith(cheater + "|Cheater|movement|")
                .contains("Simulation x").contains("ping 48 ms").contains("world 3147 79 100").contains("hovering");
        flyFor(300, 48, 19.8);
        assertThat(filed).as("not again while the first is waiting").hasSize(1);
    }

    @Test
    @DisplayName("a burst is not reported: a hundred flags in a few seconds")
    void burst() {
        for (int i = 0; i < 100; i++) {
            clock.addAndGet(50);
            service.flagged(cheater, "Cheater", CheckType.GRIM_MOVEMENT, 1, "hovering", 50, 40, 19.5, "world 1 2 3");
        }
        assertThat(filed).isEmpty();
    }

    @Test
    @DisplayName("lag is weak evidence: two minutes of flags at a high ping do not add up to a report")
    void lag() {
        flyFor(200, 900, 19.8);
        assertThat(filed).isEmpty();
    }

    @Test
    @DisplayName("switched off, nothing is filed")
    void off() {
        AntiCheatSettings off = withAutoReport(false);
        service.settings(off);
        flyFor(120, 48, 19.8);
        assertThat(filed).isEmpty();
    }

    @Test
    @DisplayName("without a moderation module taking reports it keeps trying quietly, and files as soon as there is one")
    void noSink() {
        AutoReports.clear();
        flyFor(90, 48, 19.8);
        assertThat(filed).isEmpty();
        AutoReports.provide((subject, name, kind, text) -> {
            filed.add(text);
            return AutoReports.Result.FILED;
        });
        flyFor(70, 48, 19.8);
        assertThat(filed).hasSize(1);
    }

    @Test
    @DisplayName("when the queue already holds one for them, it is not asked again for a while")
    void duplicate() {
        answer = AutoReports.Result.DUPLICATE;
        flyFor(60, 48, 19.8);
        int asked = filed.size();
        assertThat(asked).isEqualTo(1);
        flyFor(120, 48, 19.8);
        assertThat(filed).hasSize(asked);
    }

    @Test
    @DisplayName("a sustained combat hack is a report of its own kind, beside any movement report")
    void combatIsItsOwnKind() {
        for (int i = 0; i < 200; i++) {
            clock.addAndGet(1000);
            service.flagged(cheater, "Cheater", CheckType.GRIM_COMBAT, 1, "Reach: 3.6 blocks", 90, 40, 19.5, "world 1 2 3");
        }
        assertThat(filed).hasSize(1);
        assertThat(filed.getFirst()).contains("|combat|").contains("Reach");
        flyFor(120, 48, 19.8);
        assertThat(filed).hasSize(2).anyMatch(line -> line.contains("|movement|"));
    }

    @Test
    @DisplayName("inventory and client checks are no kind of report")
    void notReported() {
        for (int i = 0; i < 200; i++) {
            clock.addAndGet(1000);
            service.flagged(cheater, "Cheater", CheckType.GRIM_INVENTORY, 1, "fast", 90, 40, 19.5, "world 1 2 3");
            service.flagged(cheater, "Cheater", CheckType.CLIENT, 1, "wurst", 90, 40, 19.5, "world 1 2 3");
        }
        assertThat(filed).isEmpty();
    }

    @Test
    @DisplayName("forgetting a player drops what was held on them")
    void forget() {
        flyFor(30, 48, 19.8);
        service.forget(cheater);
        flyFor(30, 48, 19.8);
        assertThat(filed).as("two halves are not one run").isEmpty();
    }

    private static AntiCheatSettings withAutoReport(boolean on) {
        AntiCheatSettings d = AntiCheatSettings.DEFAULTS;
        return d.withAutoReport(on);
    }
}
