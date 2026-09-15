package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.conditions.DeathEndCondition;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A game mode is played <em>in</em> the lobby, not beside it: the same world, the same start block,
 * the same countdown and the same reset — the mode only adds what makes it a different game.
 *
 * <p>These are the seams that decide whether that holds: a mode can refuse a start before anybody is
 * frozen, it gets the fresh session before the clock starts, whatever it listens to goes when the run
 * is forgotten, and it may speak for the finish or decide who counts toward the goal.
 */
class SpeedrunLobbyModeTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;
    private PluginManager pluginManager;
    private SettingsStore<SpeedrunSettings> settings;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<HandlerList> handlers;

    /** A mode whose every answer a test can set. */
    private static final class ScriptedMode implements SpeedrunMode {
        Optional<String> refusal = Optional.empty();
        boolean usesDeathPolicy = true;
        boolean announces = false;
        Consumer<SpeedrunRun> whenStarted = run -> { };
        final List<SpeedrunRun> started = new ArrayList<>();
        final List<Set<UUID>> refusedAsked = new ArrayList<>();

        @Override
        public String id() {
            return "scripted";
        }

        @Override
        public String label() {
            return "Scripted";
        }

        @Override
        public Material icon() {
            return Material.TARGET;
        }

        @Override
        public Optional<String> refuseStart(SpeedrunSettings config, Set<UUID> participants) {
            refusedAsked.add(participants);
            return refusal;
        }

        @Override
        public boolean usesDeathPolicy() {
            return usesDeathPolicy;
        }

        @Override
        public boolean countsForGoal(UUID participant) {
            return ALICE.equals(participant);
        }

        @Override
        public void onStart(SpeedrunRun run) {
            started.add(run);
            whenStarted.accept(run);
        }

        @Override
        public boolean announceFinish(SpeedrunSession session, SpeedrunOutcome outcome) {
            return announces;
        }
    }

    private ScriptedMode mode;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);

        settings = new SettingsStore<>(
                SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                dataFolder.resolve("speedrun.yml"));
        settings.load();
        settings.set("world-name", "world");

        mode = new ScriptedMode();
        SpeedrunModes.clear();
        SpeedrunModes.offer(mode);

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(null);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
        handlers = mockStatic(HandlerList.class);
    }

    @AfterEach
    void tearDown() {
        handlers.close();
        bukkit.close();
        SpeedrunModes.clear();
    }

    /** A mocked player whose own scheduler runs what it is handed — see SpeedrunLobbyTest's own copy. */
    private static org.bukkit.entity.Player playerRunningItsOwnTasks() {
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        io.papermc.paper.threadedregions.scheduler.EntityScheduler scheduler =
                mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        when(scheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, Consumer.class).accept(null);
            return null;
        });
        when(player.getScheduler()).thenReturn(scheduler);
        when(player.getLocation()).thenReturn(mock(org.bukkit.Location.class));
        return player;
    }

    private SpeedrunLobby lobby() {
        return new SpeedrunLobby(plugin, settings, (participants, onComplete) -> onComplete.run());
    }

    @Test
    @DisplayName("with no mode chosen, nothing is asked of any installed mode")
    void plainRaceAsksNoMode() {
        SpeedrunLobby lobby = lobby();

        assertThat(lobby.mode()).isEmpty();
        assertThat(lobby.beginCountdown(Set.of(ALICE))).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);
        assertThat(mode.started).isEmpty();
        assertThat(mode.refusedAsked).isEmpty();
    }

    @Test
    @DisplayName("a chosen mode whose module is not installed refuses the start, rather than racing plain")
    void missingModeRefuses() {
        settings.set("game-mode", "manhunt");
        AtomicBoolean launched = new AtomicBoolean();
        SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings, (participants, onComplete) -> launched.set(true));

        assertThat(lobby.beginCountdown(Set.of(ALICE))).isEqualTo(SpeedrunLobby.StartOutcome.MODE_MISSING);
        assertThat(launched).as("nobody is frozen for a start that cannot happen").isFalse();
    }

    @Test
    @DisplayName("a mode's refusal stops the countdown before it begins, and its reason is readable")
    void modeRefusal() {
        settings.set("game-mode", "scripted");
        mode.refusal = Optional.of("manhunt.start.no-runner");
        AtomicBoolean launched = new AtomicBoolean();
        SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings, (participants, onComplete) -> launched.set(true));

        assertThat(lobby.beginCountdown(Set.of(ALICE, BOB))).isEqualTo(SpeedrunLobby.StartOutcome.REFUSED_BY_MODE);
        assertThat(lobby.refusalFor(Set.of(ALICE, BOB))).contains("manhunt.start.no-runner");
        assertThat(launched).isFalse();
        assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.READY);
    }

    @Test
    @DisplayName("the mode is handed the running session and its roster before the clock starts")
    void modeGetsTheRun() {
        settings.set("game-mode", "scripted");
        AtomicReference<SpeedrunState> stateWhenHanded = new AtomicReference<>();
        mode.whenStarted = run -> stateWhenHanded.set(run.session().state());
        SpeedrunLobby lobby = lobby();

        assertThat(lobby.beginCountdown(Set.of(ALICE, BOB))).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);

        assertThat(mode.started).hasSize(1);
        SpeedrunRun run = mode.started.getFirst();
        assertThat(run.session()).isSameAs(lobby.session().orElseThrow());
        assertThat(run.participants()).containsExactlyInAnyOrder(ALICE, BOB);
        assertThat(stateWhenHanded.get()).isEqualTo(SpeedrunState.NOT_STARTED);
        assertThat(lobby.session().orElseThrow().state()).isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    @DisplayName("the run knows its own three worlds, and nothing else")
    void runKnowsItsWorlds() {
        settings.set("game-mode", "scripted");
        lobby().beginCountdown(Set.of(ALICE));

        SpeedrunRun run = mode.started.getFirst();
        assertThat(run.isRunWorld("world")).isTrue();
        assertThat(run.isRunWorld("world_nether")).isTrue();
        assertThat(run.isRunWorld("world_the_end")).isTrue();
        assertThat(run.isRunWorld("lobby")).isFalse();
    }

    @Test
    @DisplayName("a mode that does not use the death policy gets no death condition armed behind its back")
    void deathPolicySkipped() {
        settings.set("game-mode", "scripted");
        settings.set("death-policy", "ANY");
        mode.usesDeathPolicy = false;

        lobby().beginCountdown(Set.of(ALICE));

        ArgumentCaptor<Listener> registered = ArgumentCaptor.forClass(Listener.class);
        verify(pluginManager, atLeastOnce()).registerEvents(registered.capture(), eq(plugin));
        assertThat(registered.getAllValues()).noneMatch(listener -> listener instanceof DeathEndCondition);
    }

    @Test
    @DisplayName("a mode that does not use the death policy still needs a goal — or nothing could end its run")
    void noGoalNoStart() {
        settings.set("game-mode", "scripted");
        settings.set("advancement-key", "");
        settings.set("death-policy", "ANY");
        mode.usesDeathPolicy = false;

        assertThat(lobby().beginCountdown(Set.of(ALICE))).isEqualTo(SpeedrunLobby.StartOutcome.NO_END_CONDITION);
    }

    @Test
    @DisplayName("what a mode listens to through the run is unregistered when the run is forgotten")
    void runListenersGoWithTheRun() {
        settings.set("game-mode", "scripted");
        Listener modeListener = new Listener() { };
        AtomicBoolean disarmed = new AtomicBoolean();
        mode.whenStarted = run -> {
            run.listen(modeListener);
            run.onDisarm(() -> disarmed.set(true));
        };
        SpeedrunLobby lobby = lobby();
        lobby.beginCountdown(Set.of(ALICE));
        verify(pluginManager).registerEvents(modeListener, plugin);

        // Reset with the world already gone: the run is forgotten exactly the same way, without a
        // real WorldRegenerator being asked to delete and remake anything in a test with no server.
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
        lobby.forceReset();

        handlers.verify(() -> HandlerList.unregisterAll(modeListener));
        assertThat(disarmed).isTrue();
    }

    @Test
    @DisplayName("a mode that throws while starting does not leave a plain race running in its place")
    void brokenModeAbortsTheStart() {
        settings.set("game-mode", "scripted");
        AtomicBoolean ready = new AtomicBoolean();
        mode.whenStarted = run -> {
            throw new IllegalStateException("broken on purpose");
        };
        SpeedrunLobby lobby = lobby();
        lobby.onReady(() -> ready.set(true));

        lobby.beginCountdown(Set.of(ALICE));

        assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.READY);
        assertThat(lobby.session()).isEmpty();
        assertThat(ready).as("the lobby items come back, so somebody can try again").isTrue();
    }

    @Test
    @DisplayName("a mode that speaks for the finish replaces the plain announcement")
    void modeAnnouncesTheFinish() {
        settings.set("game-mode", "scripted");
        mode.announces = true;
        Messages messages = mock(Messages.class);
        // Built before the stubbing call: a mock set up inside thenReturn(...) leaves Mockito's own
        // stubbing half finished, which it then reports against the next unrelated line.
        org.bukkit.entity.Player alice = playerRunningItsOwnTasks();
        bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
        SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings,
                (participants, onComplete) -> onComplete.run(), messages);

        lobby.beginCountdown(Set.of(ALICE));
        lobby.session().orElseThrow().finish("advancement:minecraft:end/kill_dragon");

        verify(messages, never()).send(any(), eq("speedrun.finished"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("who counts toward the goal is the mode's answer, not simply the roster")
    void goalCountsWhoTheModeSays() {
        settings.set("game-mode", "scripted");
        settings.set("require-exit-portal-after-dragon", "false");
        SpeedrunLobby lobby = lobby();
        lobby.beginCountdown(Set.of(ALICE, BOB));

        ArgumentCaptor<Listener> registered = ArgumentCaptor.forClass(Listener.class);
        verify(pluginManager, atLeastOnce()).registerEvents(registered.capture(), eq(plugin));
        var goal = registered.getAllValues().stream()
                .filter(listener -> listener instanceof de.raindancer.modules.speedrun.conditions.AdvancementEndCondition)
                .map(listener -> (de.raindancer.modules.speedrun.conditions.AdvancementEndCondition) listener)
                .findFirst().orElseThrow();

        assertThat(goal.counts(BOB)).isFalse();
        assertThat(goal.counts(ALICE)).isTrue();
    }
}
