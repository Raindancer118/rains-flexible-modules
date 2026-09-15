package de.raindancer.modules.manhunt;

import de.raindancer.core.data.settings.SettingsNavigation;
import de.raindancer.core.data.settings.SettingsRegistry;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.settings.SettingsTopic;
import de.raindancer.modules.speedrun.SpeedrunSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a server running both modules can reach either one's settings, and that the two do not claim
 * the same thing twice.
 *
 * <h2>Why this is a test in manhunt-module and not in Core</h2>
 * Core cannot write it: the thing being checked is what happens when *these two* schemas are merged,
 * and Core has never heard of either. Manhunt is the only module that compiles against both.
 *
 * <h2>What it is really pinning now</h2>
 * The old pair had a genuine collision — both declared a {@code world-name}, and the server logged
 * "the setting 'world-name' is declared by more than one plugin" on every boot. There is exactly one
 * world now and it is the lobby's, so the rule is stronger than "both are reachable": Manhunt must
 * declare nothing about the world, the goal or the reset at all.
 */
class SettingsReachabilityTest {

    @TempDir
    Path directory;

    private SettingsRegistry registry;
    private SettingsNavigation navigation;

    @BeforeEach
    void setUp() {
        registry = new SettingsRegistry();
        registry.add(store(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS, "speedrun"));
        registry.add(store(ManhuntSettings.class, ManhuntSettings.DEFAULTS, "manhunt"));
        navigation = new SettingsNavigation(registry);
    }

    private <T> SettingsStore<T> store(Class<T> type, T defaults, String name) {
        SettingsStore<T> store = new SettingsStore<>(SettingsSchema.of(type, defaults),
                directory.resolve(name + ".yml"));
        store.load();
        return store;
    }

    private static List<String> pathsOf(List<SettingsTopic> topics) {
        return topics.stream().map(SettingsTopic::path).toList();
    }

    private List<String> keysOf(String path) {
        return navigation.page(path).settings().stream().map(setting -> setting.key()).toList();
    }

    @Test
    @DisplayName("both modules have a root of their own on the front page")
    void frontPage() {
        assertThat(pathsOf(navigation.page(null).subtopics())).contains("speedrun", "manhunt");
    }

    @Test
    @DisplayName("the race, the world and the goal are the lobby's alone")
    void speedrunKeepsTheRace() {
        assertThat(keysOf("speedrun/race"))
                .contains("world-name", "advancement-key", "death-policy", "game-mode");
    }

    @Test
    @DisplayName("Manhunt declares nothing about a world, a goal or a reset — there is only the lobby's")
    void manhuntClaimsNothingOfTheLobbys() {
        List<String> manhuntKeys = SettingsSchema.of(ManhuntSettings.class, ManhuntSettings.DEFAULTS)
                .settings().stream().map(setting -> setting.key()).toList();

        assertThat(manhuntKeys)
                .doesNotContain("world-name", "advancement-key", "death-policy", "seed", "countdown-seconds");
    }

    @Test
    @DisplayName("what is left is the compass, the sides and the door, each reachable")
    void manhuntsOwnPages() {
        assertThat(pathsOf(navigation.page("manhunt").subtopics()))
                .containsExactlyInAnyOrder("manhunt/tracker", "manhunt/sides", "manhunt/doors");
        assertThat(keysOf("manhunt/tracker"))
                .contains("tracker-cross-world", "tracker-hunter-may-choose", "tracker-show-distance",
                        "tracker-refresh-ticks");
        assertThat(keysOf("manhunt/sides")).contains("runner-self-join");
        assertThat(keysOf("manhunt/doors")).contains("close-whitelist-on-start");
    }
}
