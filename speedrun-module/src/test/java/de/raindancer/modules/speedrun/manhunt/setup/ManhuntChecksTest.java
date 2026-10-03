package de.raindancer.modules.speedrun.manhunt.setup;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.modules.speedrun.SpeedrunMode;
import de.raindancer.modules.speedrun.SpeedrunPreflight;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.util.PermissionNodes;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What Manhunt adds to the lobby's one pre-flight check: the sides and the door, each with the fix
 * a click away — and nothing the lobby already checks (the world, the goal, a run under way).
 */
@DisplayName("Manhunt's pre-flight checks")
class ManhuntChecksTest {

    @TempDir
    Path directory;

    private final AtomicReference<Preflight.Situation> situation = new AtomicReference<>();
    private final List<String> done = new ArrayList<>();
    private SettingsStore<ManhuntSettings> settings;
    private ManhuntChecks checks;

    @BeforeEach
    void setUp() {
        settings = new SettingsStore<>(SettingsSchema.of(ManhuntSettings.class, ManhuntSettings.DEFAULTS),
                directory.resolve("m.yml"));
        settings.load();
        checks = new ManhuntChecks(new ManhuntChecks.Desk() {
            public Preflight.Situation situation() {
                return situation.get();
            }

            public void randomRunner() {
                done.add("random");
            }

            public void balance() {
                done.add("balance");
            }

            public void keepDoorOpen() {
                done.add("keep-open");
            }

            public void closeDoorOnStart() {
                done.add("close-on-start");
            }
        }, settings, player -> done.add("sides"));
    }

    private static Preflight.Situation good() {
        return new Preflight.Situation(true, false, 4, 1, List.of(), "minecraft:end/kill_dragon", true,
                false, false, 0.5);
    }

    private Optional<SpeedrunPreflight.Check> check(String id) {
        return checks.checks().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    private void click(String id) {
        SpeedrunPreflight.Check check = check(id).orElseThrow();
        assertThat(check.fix()).isEqualTo(SpeedrunPreflight.Fix.MODE);
        assertThat(check.modeFix().node()).as("the node of /manhunt's own words").isEqualTo(PermissionNodes.ADMIN);
        check.modeFix().apply().accept(mock(Player.class));
    }

    @Test
    @DisplayName("a good lobby is ready, every check green")
    void ready() {
        situation.set(good());

        assertThat(checks.checks()).isNotEmpty().allMatch(SpeedrunPreflight.Check::ok);
    }

    @Test
    @DisplayName("nobody running blocks the start, with one click to pick somebody at random")
    void noRunner() {
        situation.set(new Preflight.Situation(true, false, 4, 0, List.of(), "x:y", true, false, false, 0.5));

        assertThat(check("manhunt-runner").orElseThrow().stopsTheStart()).isTrue();
        click("manhunt-runner");
        assertThat(done).containsExactly("random");
    }

    @Test
    @DisplayName("everybody running blocks it too, fixed by balancing")
    void noHunter() {
        situation.set(new Preflight.Situation(true, false, 3, 3, List.of(), "x:y", true, false, false, 0.5));

        assertThat(check("manhunt-hunter").orElseThrow().stopsTheStart()).isTrue();
        click("manhunt-hunter");
        assertThat(done).containsExactly("balance");
    }

    @Test
    @DisplayName("warnings: a Runner not here, the door about to shut, and lopsided sides — each a click away")
    void warnings() {
        situation.set(new Preflight.Situation(true, false, 4, 1, List.of("Away"), "x:y", true, true, false, 0.1));

        for (String id : List.of("manhunt-away", "manhunt-door", "manhunt-even")) {
            SpeedrunPreflight.Check check = check(id).orElseThrow();
            assertThat(check.ok()).as(id).isFalse();
            assertThat(check.blocking()).as(id).isFalse();
        }
        assertThat(check("manhunt-away").orElseThrow().detail()).contains("Away");
        click("manhunt-away");
        click("manhunt-door");
        click("manhunt-even");
        assertThat(done).containsExactly("sides", "keep-open", "balance");
    }

    @Test
    @DisplayName("an already shut door is no news")
    void shutDoor() {
        situation.set(new Preflight.Situation(true, false, 4, 1, List.of(), "x:y", true, true, true, 0.5));

        assertThat(check("manhunt-door").orElseThrow().ok()).isTrue();
    }

    @Test
    @DisplayName("one player alone is blocked, and nothing else is said about the sides")
    void alone() {
        situation.set(new Preflight.Situation(true, false, 1, 1, List.of(), "", false, false, false, 0.5));

        assertThat(checks.checks()).singleElement().satisfies(check -> {
            assertThat(check.id()).isEqualTo("manhunt-two");
            assertThat(check.stopsTheStart()).isTrue();
        });
    }

    @Test
    @DisplayName("a hunt already on: the lobby's own check says so, Manhunt adds nothing")
    void huntOn() {
        situation.set(new Preflight.Situation(true, true, 4, 1, List.of(), "", false, false, false, 0.5));

        assertThat(checks.checks()).isEmpty();
    }

    @Test
    @DisplayName("the goal is the lobby's to check — never a Manhunt check of its own")
    void noGoalCheck() {
        situation.set(new Preflight.Situation(true, false, 4, 1, List.of(), "minecraft:nope", false, false, false, 0.5));

        assertThat(checks.checks()).extracting(SpeedrunPreflight.Check::id).noneMatch(id -> id.contains("goal"));
    }

    @Test
    @DisplayName("the setup assistant asks for a preset and the door; each answer does what it says")
    void questions() {
        List<SpeedrunMode.SetupQuestion> asked = checks.questions();

        assertThat(asked).extracting(SpeedrunMode.SetupQuestion::question)
                .containsExactly("Which kind of hunt?", "What does a start do to the door?");
        asked.getFirst().answers().get(1).pick().run();
        assertThat(settings.current().runnerLives()).as("casual").isEqualTo(2);
        asked.get(1).answers().get(1).pick().run();
        assertThat(done).containsExactly("close-on-start");
    }

    @Nested
    @DisplayName("presets")
    class Presets {

        @Test
        @DisplayName("every preset applies cleanly — each key it names is a real setting with a valid value")
        void allApply() {
            for (Preset preset : Preset.values()) {
                assertThat(preset.applyTo(settings)).as(preset.name()).isEqualTo(preset.changes().size());
            }
        }

        @Test
        @DisplayName("casual is gentler than classic, sweaty is harder")
        void shapes() {
            Preset.CASUAL.applyTo(settings);
            assertThat(settings.current().runnerLives()).isGreaterThan(1);
            assertThat(settings.current().trackerTeamCompass()).isTrue();

            Preset.SWEATY.applyTo(settings);
            assertThat(settings.current().runnerLives()).isEqualTo(1);
            assertThat(settings.current().hunterRespawnDelaySeconds()).isPositive();

            Preset.CLASSIC.applyTo(settings);
            assertThat(settings.current()).isEqualTo(ManhuntSettings.DEFAULTS);
        }
    }
}
