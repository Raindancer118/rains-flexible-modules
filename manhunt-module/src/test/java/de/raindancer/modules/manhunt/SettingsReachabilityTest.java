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
 * That a server running both modules can still reach either one's settings from the {@code /settings}
 * front page.
 *
 * <h2>Why this is a test in manhunt-module and not in Core</h2>
 * Core cannot write it: the thing being checked is what happens when *these two* schemas are merged,
 * and Core has never heard of either. Manhunt is the only module that already compiles against both
 * (speedrun-module is a {@code provided} dependency of it), so it is the only place the real pair can
 * be put through the real registry.
 *
 * <p>It is here at all because of a report that Speedrun's settings had stopped appearing in
 * {@code /settings} once Manhunt was installed beside it. They had not: {@code /settings list} listed
 * every one of them throughout, and the merged tree held them. What had happened is that Manhunt
 * arrived with a root of its own while Speedrun's single page sat under Core's "Server settings",
 * one level further down and beside four pages of Core's own — so the answer was to give Speedrun a
 * root too, and this test is what stops either of them quietly losing one again.
 */
class SettingsReachabilityTest {

    @TempDir
    Path directory;

    private SettingsNavigation navigation;

    @BeforeEach
    void setUp() {
        SettingsRegistry registry = new SettingsRegistry();
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

    @Test
    @DisplayName("both modules have a root of their own on the front page")
    void frontPage() {
        List<String> roots = pathsOf(navigation.page(null).subtopics());

        assertThat(roots).contains("speedrun", "manhunt");
    }

    @Test
    @DisplayName("Speedrun's three pages hang off that root")
    void speedrunIsReachable() {
        assertThat(pathsOf(navigation.page("speedrun").subtopics()))
                .containsExactlyInAnyOrder("speedrun/race", "speedrun/hazard", "speedrun/start");
    }

    @Test
    @DisplayName("every one of Speedrun's settings is on one of them, and none went missing")
    void speedrunKeepsItsSettings() {
        assertThat(keysOf("speedrun/race"))
                .contains("world-name", "advancement-key", "death-policy",
                        "require-exit-portal-after-dragon");
        assertThat(keysOf("speedrun/hazard"))
                .contains("creeper-spawn-chance-on-break-percent",
                        "charged-creeper-chance-on-break-percent",
                        "creeper-spawn-chance-on-container-percent",
                        "charged-creeper-chance-on-container-percent");
        assertThat(keysOf("speedrun/start")).contains("start-point-set", "start-x", "start-pitch");
    }

    /**
     * Nothing is filed under Core's own "Server settings" any more — the whole point of the move. A
     * page left behind there would be the same setting reachable twice, which is worse than either
     * place on its own.
     */
    @Test
    @DisplayName("Speedrun left nothing behind under the server settings")
    void nothingLeftUnderConfig() {
        assertThat(pathsOf(navigation.page("config").subtopics()))
                .doesNotContain("config/speedrun");
    }

    private List<String> keysOf(String path) {
        return navigation.page(path).settings().stream().map(setting -> setting.key()).toList();
    }

    @Test
    @DisplayName("Manhunt's own pages are reachable too, and did not swallow Speedrun's")
    void manhuntIsReachable() {
        assertThat(pathsOf(navigation.page("manhunt").subtopics()))
                .contains("manhunt/tracker", "manhunt/world");
        assertThat(navigation.page("manhunt/world").settings())
                .extracting(setting -> setting.key())
                .contains("world-name");
    }
}
