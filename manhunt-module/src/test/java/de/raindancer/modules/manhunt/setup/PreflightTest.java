package de.raindancer.modules.manhunt.setup;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.modules.manhunt.ManhuntSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the pre-flight check")
class PreflightTest {

    /** A lobby that would start a good hunt: four players, one Runner, a goal the server knows. */
    private static Preflight.Situation good() {
        return new Preflight.Situation(true, false, 4, 1, List.of(), "minecraft:end/kill_dragon", true,
                false, false, 0.45);
    }

    private static List<String> keys(Preflight.Situation situation) {
        return Preflight.check(situation).stream().map(Preflight.Check::key).toList();
    }

    @Test
    @DisplayName("a good lobby is ready, with nothing to fix")
    void ready() {
        assertThat(Preflight.check(good())).isEmpty();
        assertThat(Preflight.ready(Preflight.check(good()))).isTrue();
    }

    @Test
    @DisplayName("nobody running blocks the start, with one click to pick somebody at random")
    void noRunner() {
        Preflight.Situation situation = new Preflight.Situation(true, false, 4, 0, List.of(), "x:y", true,
                false, false, 0.5);

        List<Preflight.Check> checks = Preflight.check(situation);

        assertThat(checks).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("no-runner");
            assertThat(check.severity()).isEqualTo(Preflight.Severity.BLOCKER);
            assertThat(check.fix()).isEqualTo(Preflight.Fix.PICK_RANDOM_RUNNER);
        });
        assertThat(Preflight.ready(checks)).isFalse();
    }

    @Test
    @DisplayName("everybody running blocks it too, fixed by balancing")
    void noHunter() {
        assertThat(Preflight.check(new Preflight.Situation(true, false, 3, 3, List.of(), "x:y", true,
                false, false, 0.9))).extracting(Preflight.Check::fix).contains(Preflight.Fix.AUTO_BALANCE);
    }

    @Test
    @DisplayName("a goal the server does not know can never be reached: blocked, fixed by removing it")
    void goalUnknown() {
        Preflight.Situation situation = new Preflight.Situation(true, false, 4, 1, List.of(), "minecraft:nope",
                false, false, false, 0.5);

        assertThat(Preflight.check(situation)).anySatisfy(check -> {
            assertThat(check.key()).isEqualTo("goal-unknown");
            assertThat(check.fix()).isEqualTo(Preflight.Fix.REMOVE_GOAL);
            assertThat(check.severity()).isEqualTo(Preflight.Severity.BLOCKER);
        });
    }

    @Test
    @DisplayName("no goal is fine — said, with a goal one click away")
    void noGoal() {
        Preflight.Situation situation = new Preflight.Situation(true, false, 4, 1, List.of(), "", false,
                false, false, 0.5);

        List<Preflight.Check> checks = Preflight.check(situation);

        assertThat(checks).singleElement().satisfies(check -> {
            assertThat(check.severity()).isEqualTo(Preflight.Severity.INFO);
            assertThat(check.fix()).isEqualTo(Preflight.Fix.CHOOSE_GOAL);
        });
        assertThat(Preflight.ready(checks)).isTrue();
    }

    @Test
    @DisplayName("warnings: a Runner not here, the door about to shut, and lopsided sides")
    void warnings() {
        Preflight.Situation situation = new Preflight.Situation(true, false, 4, 1, List.of("Away"),
                "x:y", true, true, false, 0.05);

        assertThat(keys(situation)).containsExactlyInAnyOrder("runner-away", "whitelist-will-close", "lopsided");
        assertThat(Preflight.ready(Preflight.check(situation))).isTrue();
    }

    @Test
    @DisplayName("an already shut door is no news")
    void doorAlreadyShut() {
        assertThat(keys(new Preflight.Situation(true, false, 4, 1, List.of(), "x:y", true, true, true, 0.5)))
                .doesNotContain("whitelist-will-close");
    }

    @Test
    @DisplayName("no lobby, a hunt already on, or one player alone: blocked, and nothing else said")
    void cannotStartAtAll() {
        assertThat(keys(new Preflight.Situation(false, false, 4, 1, List.of(), "", false, false, false, 0.5)))
                .containsExactly("no-lobby");
        assertThat(keys(new Preflight.Situation(true, true, 4, 1, List.of(), "", false, false, false, 0.5)))
                .containsExactly("hunt-running");
        assertThat(keys(new Preflight.Situation(true, false, 1, 1, List.of(), "", false, false, false, 0.5)))
                .containsExactly("too-few");
    }

    @Test
    @DisplayName("every check has its wording")
    void wording() throws Exception {
        String yaml = java.nio.file.Files.readString(Path.of(
                "src/main/resources/de/raindancer/modules/manhunt/messages.yml"));
        for (String key : List.of("no-lobby", "hunt-running", "too-few", "no-runner", "no-hunter", "goal-unknown",
                "no-goal", "runner-away", "whitelist-will-close", "lopsided")) {
            assertThat(yaml).contains("    " + key + ":");
        }
    }

    @Nested
    @DisplayName("presets")
    class Presets {

        @TempDir
        Path directory;

        private SettingsStore<ManhuntSettings> store() {
            SettingsStore<ManhuntSettings> store = new SettingsStore<>(
                    SettingsSchema.of(ManhuntSettings.class, ManhuntSettings.DEFAULTS), directory.resolve("m.yml"));
            store.load();
            return store;
        }

        @Test
        @DisplayName("every preset applies cleanly — each key it names is a real setting with a valid value")
        void allApply() {
            for (Preset preset : Preset.values()) {
                SettingsStore<ManhuntSettings> store = store();
                assertThat(preset.applyTo(store)).as(preset.name()).isEqualTo(preset.changes().size());
            }
        }

        @Test
        @DisplayName("casual is gentler than classic, sweaty is harder")
        void shapes() {
            SettingsStore<ManhuntSettings> store = store();
            Preset.CASUAL.applyTo(store);
            assertThat(store.current().runnerLives()).isGreaterThan(1);
            assertThat(store.current().trackerTeamCompass()).isTrue();

            Preset.SWEATY.applyTo(store);
            assertThat(store.current().runnerLives()).isEqualTo(1);
            assertThat(store.current().hunterRespawnDelaySeconds()).isPositive();

            Preset.CLASSIC.applyTo(store);
            assertThat(store.current()).isEqualTo(ManhuntSettings.DEFAULTS);
        }
    }
}
