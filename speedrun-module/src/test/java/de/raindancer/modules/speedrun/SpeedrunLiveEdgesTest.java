package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.actionbar.ActionBarSink;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.modules.speedrun.conditions.DeathEndCondition;
import de.raindancer.modules.speedrun.conditions.DragonExitEndCondition;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens on a live server between the moments the other tests start from: people leaving in
 * the middle of a countdown, settings changed under a running race, a resume over an End whose
 * dragon is long dead, the plugin going away mid-run. Each test is one edge of state × event × who.
 */
class SpeedrunLiveEdgesTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;
    private PluginManager pluginManager;
    private SettingsStore<SpeedrunSettings> settings;
    private MockedStatic<Bukkit> bukkit;
    private World world;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(plugin.isEnabled()).thenReturn(true);
        settings = new SettingsStore<>(SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                dataFolder.resolve("speedrun.yml"));
        settings.load();
        settings.set("world-name", "world");
        SpeedrunModes.clear();

        bukkit = mockStatic(Bukkit.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getPlayers()).thenReturn(List.of());
        bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(null);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
        SpeedrunModes.clear();
    }

    private static Player online(UUID id) {
        Player player = mock(Player.class, Mockito.RETURNS_DEEP_STUBS);
        when(player.getUniqueId()).thenReturn(id);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, Consumer.class).accept(null);
            return null;
        });
        when(player.getScheduler()).thenReturn(scheduler);
        return player;
    }

    /** A lobby whose countdown waits for the test to call zero. */
    private SpeedrunLobby lobbyWithManualCountdown(AtomicReference<Runnable> atZero) {
        return new SpeedrunLobby(plugin, settings, (participants, onComplete) -> atZero.set(onComplete));
    }

    private void globalSchedulerRunsImmediately() {
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
        Mockito.doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(scheduler).execute(eq(plugin), any(Runnable.class));
    }

    private List<Listener> registered() {
        ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
        verify(pluginManager, Mockito.atLeast(0)).registerEvents(captor.capture(), eq(plugin));
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("COUNTDOWN × quit")
    class QuittingDuringTheCountdown {

        /**
         * Somebody who left three seconds before "go" was never prepared — nothing of an offline
         * player can be reset — so racing them would let them come back with last round's gear, and
         * their absence would keep the clock running for nobody.
         */
        @Test
        @DisplayName("a racer who quit during the countdown does not race; the rest do")
        void aQuitterIsDropped() {
            Player alice = online(ALICE);
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
            AtomicReference<Runnable> atZero = new AtomicReference<>();
            SpeedrunLobby lobby = lobbyWithManualCountdown(atZero);
            lobby.beginCountdown(Set.of(ALICE, BOB));   // Bob is offline by zero

            atZero.get().run();

            assertThat(lobby.session().orElseThrow().participants()).containsExactly(ALICE);
        }

        @Test
        @DisplayName("everybody quitting during the countdown starts nothing, and the lobby is ready again")
        void everybodyQuit() {
            AtomicReference<Runnable> atZero = new AtomicReference<>();
            SpeedrunLobby lobby = lobbyWithManualCountdown(atZero);
            AtomicInteger ready = new AtomicInteger();
            lobby.onReady(ready::incrementAndGet);
            lobby.beginCountdown(Set.of(ALICE, BOB));

            atZero.get().run();

            assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.READY);
            assertThat(lobby.session()).isEmpty();
            assertThat(ready).hasValue(1);
        }
    }

    @Nested
    @DisplayName("RUNNING × settings changed")
    class SettingsChangedMidRun {

        /**
         * The lobby world's name decides what a reset deletes. Changed in /settings mid-run, it used
         * to point the reset at the newly named world — a world nobody had raced in, possibly one in
         * use — and leave the played one standing.
         */
        @Test
        @DisplayName("world-name changed mid-run: the run keeps its world, and a reset remakes that one")
        void worldNameIsPinnedForTheRun() {
            World other = mock(World.class);
            when(other.getName()).thenReturn("other");
            bukkit.when(() -> Bukkit.getWorld("other")).thenReturn(other);
            when(world.getWorldFolder()).thenReturn(dataFolder.resolve("w").toFile());
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mock(World.class)));
            bukkit.when(() -> Bukkit.unloadWorld(world, false)).thenReturn(true);
            globalSchedulerRunsImmediately();
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            lobby.start(Set.of(ALICE));

            settings.set("world-name", "other");
            assertThat(lobby.config().worldName()).isEqualTo("world");

            try (MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class,
                    Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF),
                    (creator, context) -> when(creator.createWorld()).thenReturn(mock(World.class)))) {
                lobby.forceReset();
            }

            bukkit.verify(() -> Bukkit.unloadWorld(world, false));
            bukkit.verify(() -> Bukkit.unloadWorld(other, false), never());
            assertThat(lobby.config().worldName()).isEqualTo("other");
        }

        @Test
        @DisplayName("world-name changed mid-countdown: the run is built in the world everybody is frozen in")
        void worldNameIsPinnedFromTheCountdown() {
            AtomicReference<Runnable> atZero = new AtomicReference<>();
            SpeedrunLobby lobby = lobbyWithManualCountdown(atZero);
            lobby.beginCountdown(Set.of(ALICE));

            settings.set("world-name", "other");

            assertThat(lobby.config().worldName()).isEqualTo("world");
        }

        @Test
        @DisplayName("the death policy switched on mid-run arms nothing in the run already going")
        void deathPolicyIsReadAtTheStart() {
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            lobby.start(Set.of(ALICE));

            settings.set("death-policy", "ANY");

            assertThat(registered()).noneMatch(listener -> listener instanceof DeathEndCondition);
        }

        @Test
        @DisplayName("restart-when-run-ends switched off mid-run is honoured when the run finishes")
        void restartSettingIsReadAtTheFinish() {
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            List<Runnable> scheduled = new ArrayList<>();
            lobby.schedulesLaterWith((ticks, task) -> scheduled.add(task));
            lobby.start(Set.of(ALICE));

            settings.set("restart-when-run-ends", "false");
            lobby.session().orElseThrow().finish("done");

            assertThat(scheduled).isEmpty();
        }
    }

    @Nested
    @DisplayName("/speedrunresume")
    class Resume {

        @Test
        @DisplayName("with the run's world unloaded it says so — not that nobody is there")
        void worldUnloaded() {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);

            assertThat(lobby.resume(lobby.presentInRunWorlds(), Duration.ZERO))
                    .isEqualTo(SpeedrunLobby.StartOutcome.WORLD_MISSING);
        }

        @Test
        @DisplayName("during a countdown it is refused, and the countdown carries on")
        void duringACountdown() {
            AtomicReference<Runnable> atZero = new AtomicReference<>();
            SpeedrunLobby lobby = lobbyWithManualCountdown(atZero);
            lobby.beginCountdown(Set.of(ALICE));

            assertThat(lobby.resume(Set.of(ALICE), Duration.ZERO)).isEqualTo(SpeedrunLobby.StartOutcome.NOT_READY);
            assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.COUNTDOWN);
        }

        @Test
        @DisplayName("somebody lying dead in the run's world is picked up like everybody else")
        void picksUpTheDead() {
            Player dead = online(ALICE);
            when(dead.isDead()).thenReturn(true);
            when(world.getPlayers()).thenReturn(List.of(dead));
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);

            assertThat(lobby.presentInRunWorlds()).containsExactly(ALICE);
        }

        private World runEndWithDragon(boolean previouslyKilled) {
            World end = mock(World.class);
            when(end.getName()).thenReturn("world_the_end");
            when(end.getEnvironment()).thenReturn(World.Environment.THE_END);
            DragonBattle battle = mock(DragonBattle.class);
            when(battle.hasBeenPreviouslyKilled()).thenReturn(previouslyKilled);
            when(end.getEnderDragonBattle()).thenReturn(battle);
            bukkit.when(() -> Bukkit.getWorld("world_the_end")).thenReturn(end);
            return end;
        }

        private void walkOutOf(World end) {
            Location from = mock(Location.class);
            when(from.getWorld()).thenReturn(end);
            PlayerPortalEvent exit = new PlayerPortalEvent(mock(Player.class), from, mock(Location.class),
                    PlayerTeleportEvent.TeleportCause.END_PORTAL);
            when(exit.getPlayer().getUniqueId()).thenReturn(ALICE);
            registered().stream().filter(DragonExitEndCondition.class::isInstance)
                    .map(DragonExitEndCondition.class::cast)
                    .forEach(condition -> condition.onExitPortal(exit));
        }

        /** Restarted after the kill, before the walk out: the portal is open and must still win it. */
        @Test
        @DisplayName("resumed after the dragon already died, the exit portal still wins the run")
        void dragonAlreadyDead() {
            World end = runEndWithDragon(true);
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            lobby.resume(Set.of(ALICE), Duration.ofMinutes(30));

            walkOutOf(end);

            assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.FINISHED);
        }

        @Test
        @DisplayName("an ordinary start never assumes the dragon is dead")
        void freshStartDoesNotAssume() {
            World end = runEndWithDragon(true);
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            lobby.start(Set.of(ALICE));

            walkOutOf(end);

            assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.RUNNING);
        }

        @Test
        @DisplayName("resumed with the dragon still alive, the exit portal alone wins nothing")
        void dragonStillAlive() {
            World end = runEndWithDragon(false);
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            lobby.resume(Set.of(ALICE), Duration.ZERO);

            walkOutOf(end);

            assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.RUNNING);
        }
    }

    @Nested
    @DisplayName("/speedruntime in every state")
    class SettingTheClock {

        @Test
        @DisplayName("PAUSED: set, and still paused")
        void whilePaused() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            session.start();
            session.pauseForEmptyRoster();

            assertThat(session.setElapsed(Duration.ofMinutes(10))).isTrue();
            assertThat(session.elapsed()).isEqualTo(Duration.ofMinutes(10));
            assertThat(session.state()).isEqualTo(SpeedrunState.PAUSED);
        }

        @Test
        @DisplayName("FINISHED or not yet started: refused — the result stands")
        void finishedOrNotStarted() {
            SpeedrunSession notStarted = new SpeedrunSession(Set.of(ALICE));
            SpeedrunSession finished = new SpeedrunSession(Set.of(ALICE));
            finished.start();
            finished.finish("done");

            assertThat(notStarted.setElapsed(Duration.ofMinutes(1))).isFalse();
            assertThat(finished.setElapsed(Duration.ofMinutes(1))).isFalse();
        }

        @Test
        @DisplayName("COUNTDOWN: there is no session, so no clock to set")
        void duringACountdown() {
            SpeedrunLobby lobby = lobbyWithManualCountdown(new AtomicReference<>());
            lobby.beginCountdown(Set.of(ALICE));

            assertThat(lobby.session()).isEmpty();
        }

        @Test
        @DisplayName("0 is a clock reading; numbers too big for a clock are refused rather than thrown")
        void oddInput() {
            assertThat(RunClock.parse("0")).contains(Duration.ZERO);
            assertThat(RunClock.parse("0:00")).contains(Duration.ZERO);
            assertThat(RunClock.parse("99999999999999999999:00")).isEmpty();
            assertThat(RunClock.parse("9999999999999h")).isEmpty();
            assertThat(RunClock.parse("100000:00:00")).isEmpty();
            assertThat(RunClock.parse("-5m")).isEmpty();
            assertThat(RunClock.parse("")).isEmpty();
            assertThat(RunClock.parse("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("RUNNING × roster shrinking")
    class RosterShrinking {

        /** Bob is offline; Alice, the only one online, is taken off the roster (Manhunt's leave). */
        @Test
        @DisplayName("taking the last online racer off the roster pauses the clock like their quit would")
        void removalLeavingNobodyOnlinePauses() {
            Player alice = online(ALICE);
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            lobby.start(Set.of(ALICE, BOB));
            SpeedrunSession session = lobby.session().orElseThrow();

            session.removeParticipant(ALICE);

            assertThat(session.state()).isEqualTo(SpeedrunState.PAUSED);
        }

        @Test
        @DisplayName("taking somebody off while another racer is online keeps the clock running")
        void removalWithSomebodyOnlineKeepsRunning() {
            Player alice = online(ALICE);
            Player bob = online(BOB);
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
            bukkit.when(() -> Bukkit.getPlayer(BOB)).thenReturn(bob);
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            lobby.start(Set.of(ALICE, BOB));
            SpeedrunSession session = lobby.session().orElseThrow();

            session.removeParticipant(ALICE);

            assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
        }
    }

    @Nested
    @DisplayName("plugin disable mid-run")
    class Shutdown {

        /**
         * The clock is drawn into Core's action bars until cleared, and Core outlives this plugin: a
         * reload mid-run left every racer with a clock frozen at the moment of the reload.
         */
        @Test
        @DisplayName("takes the clock off every bar and lets the game mode clean up")
        void clearsTheClockAndTheMode() {
            Map<UUID, Component> shown = new HashMap<>();
            ActionBarSink sink = (player, message) -> {
                if (message.equals(Component.empty())) {
                    shown.remove(player);
                } else {
                    shown.put(player, message);
                }
            };
            SpeedrunTimerDisplay display = new SpeedrunTimerDisplay(new ActionBars(sink, () -> 0L),
                    task -> () -> { });
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings,
                    (participants, onComplete) -> onComplete.run(), display);
            lobby.start(Set.of(ALICE));
            assertThat(shown).containsKey(ALICE);

            lobby.shutdown();

            assertThat(shown).doesNotContainKey(ALICE);
        }

        @Test
        @DisplayName("whatever a game mode hung on the run is cleaned up")
        void disarmsTheModesRun() {
            AtomicBoolean cleaned = new AtomicBoolean();
            SpeedrunModes.offer(new SpeedrunMode() {
                public String id() { return "test"; }
                public String label() { return "Test"; }
                public org.bukkit.Material icon() { return org.bukkit.Material.STONE; }
                public java.util.Optional<String> refuseStart(SpeedrunSettings c, Set<UUID> p) {
                    return java.util.Optional.empty();
                }
                public void onStart(SpeedrunRun run) { run.onDisarm(() -> cleaned.set(true)); }
            });
            settings.set("game-mode", "test");
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            lobby.start(Set.of(ALICE));

            lobby.shutdown();

            assertThat(cleaned).isTrue();
        }

        @Test
        @DisplayName("never touches the world — a disable is not a reset")
        void doesNotRegenerate() {
            settings.set("restart-when-run-ends", "false");
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            lobby.start(Set.of(ALICE));
            lobby.session().orElseThrow().finish("done");

            lobby.shutdown();

            bukkit.verify(Bukkit::getGlobalRegionScheduler, never());
            bukkit.verify(() -> Bukkit.unloadWorld(any(World.class), Mockito.anyBoolean()), never());
        }
    }
}
