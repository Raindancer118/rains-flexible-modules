package de.raindancer.modules.speedrun.manhunt.setup;

import de.raindancer.modules.speedrun.Histories;
import de.raindancer.modules.speedrun.manhunt.mode.ManhuntMode;

import de.raindancer.core.data.settings.SettingsRegistry;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.model.ManhuntTeams;
import de.raindancer.modules.speedrun.manhunt.stats.StatsStore;
import de.raindancer.modules.speedrun.SpeedrunSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the front desk the hub and the commands share")
class HuntDeskTest {

    private static final UUID A = UUID.nameUUIDFromBytes("a".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("b".getBytes());
    private static final UUID C = UUID.nameUUIDFromBytes("c".getBytes());
    private static final UUID D = UUID.nameUUIDFromBytes("d".getBytes());

    @TempDir
    Path directory;

    private final Set<UUID> present = new LinkedHashSet<>(List.of(A, B, C, D));
    private final Map<UUID, String> names = Map.of(A, "A", B, "B", C, "C", D, "D");
    private final AtomicBoolean hunting = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<String> knownAdvancements = Set.of("minecraft:end/kill_dragon", "minecraft:story/enter_the_nether");
    private ManhuntTeams teams;
    private SettingsStore<ManhuntSettings> settings;
    private SettingsRegistry registry;
    private StatsStore stats;
    private final de.raindancer.modules.speedrun.SpeedrunHistory history =
            de.raindancer.modules.speedrun.Histories.inMemory();
    private HuntDesk desk;

    @BeforeEach
    void setUp() {
        teams = new ManhuntTeams(hunting::get);
        settings = store(ManhuntSettings.class, ManhuntSettings.DEFAULTS, "manhunt");
        registry = new SettingsRegistry();
        registry.add(settings);
        registry.add(store(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS, "speedrun"));
        stats = new StatsStore(() -> history, ManhuntMode.ID);
        desk = new HuntDesk(() -> present, id -> names.getOrDefault(id, "somebody"), settings, () -> registry, teams,
                hunting::get, closed::get, stats, knownAdvancements::contains, new Random(3));
    }

    private <T> SettingsStore<T> store(Class<T> type, T defaults, String name) {
        SettingsStore<T> store = new SettingsStore<>(SettingsSchema.of(type, defaults), directory.resolve(name + ".yml"));
        store.load();
        return store;
    }

    @Test
    @DisplayName("the situation is read off the lobby as it stands")
    void situation() {
        teams.joinRunners(A);
        teams.joinRunners(UUID.randomUUID());   // a Runner who is not here

        Preflight.Situation situation = desk.situation();

        assertThat(situation.lobbyRunning()).isTrue();
        assertThat(situation.present()).isEqualTo(4);
        assertThat(situation.runnersPresent()).isEqualTo(1);
        assertThat(situation.runnersAway()).hasSize(1);
        assertThat(situation.goalKey()).isEqualTo(SpeedrunSettings.DEFAULTS.advancementKey());
        assertThat(situation.goalKnown()).isTrue();
    }

    @Test
    @DisplayName("without the speedrun lobby, the situation says so")
    void noLobby() {
        HuntDesk lonely = new HuntDesk(() -> present, id -> "x", settings, () -> null, teams, hunting::get,
                closed::get, stats, key -> true, new Random(1));

        assertThat(lonely.situation().lobbyRunning()).isFalse();
    }

    @Test
    @DisplayName("balancing puts everybody here on a side, and nobody who is not here")
    void balance() {
        UUID away = UUID.randomUUID();
        teams.joinRunners(away);

        HuntDesk.Result result = desk.balance();

        assertThat(result.done()).isTrue();
        assertThat(teams.everybody()).containsAll(present);
        assertThat(teams.runners()).isNotEmpty().doesNotContain(away);
        assertThat(teams.everybody()).doesNotContain(away);
    }

    @Test
    @DisplayName("random Runners: exactly that many, the rest hunting")
    void random() {
        teams.joinRunners(A);
        teams.joinRunners(B);

        HuntDesk.Result result = desk.randomRunners(1);

        assertThat(result.done()).isTrue();
        assertThat(teams.runners()).hasSize(1);
        assertThat(teams.hunters()).hasSize(3);
        assertThat(result.runners()).containsExactlyElementsOf(teams.runners().stream().map(names::get).toList());
    }

    @Test
    @DisplayName("neither is done during a hunt — the sides are frozen")
    void notMidHunt() {
        hunting.set(true);

        assertThat(desk.balance().done()).isFalse();
        assertThat(desk.randomRunners(1).done()).isFalse();
        assertThat(teams.everybody()).isEmpty();
    }

    @Test
    @DisplayName("with fewer than two here, there is nothing to split")
    void tooFew() {
        present.clear();
        present.add(A);

        assertThat(desk.balance().done()).isFalse();
        assertThat(desk.randomRunners(1).done()).isFalse();
    }

    @Test
    @DisplayName("the goal is the lobby's own setting: read, set to a known advancement, never to an unknown one")
    void goal() {
        assertThat(desk.setGoal("minecraft:story/enter_the_nether")).isTrue();
        assertThat(desk.goal()).isEqualTo("minecraft:story/enter_the_nether");
        assertThat(desk.setGoal("minecraft:not/real")).isFalse();
        assertThat(desk.goal()).isEqualTo("minecraft:story/enter_the_nether");
    }

    @Test
    @DisplayName("keeping the door open turns the whitelist setting off")
    void keepDoorOpen() {
        settings.set("close-whitelist-on-start", "true");

        desk.keepDoorOpen();

        assertThat(settings.current().closeWhitelistOnStart()).isFalse();
    }

    @Test
    @DisplayName("the Runners' chance follows the ratings of who is on which side")
    void chance() {
        teams.joinRunners(A);

        assertThat(desk.situation().runnersExpected()).isBetween(0.0, 0.5);
    }
}
