package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.actionbar.ActionBarSink;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.bukkit.Server;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpeedrunLobbyTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;
    private PluginManager pluginManager;
    private SettingsStore<SpeedrunSettings> settings;

    /**
     * Makes a mocked player's own scheduler run what it is handed, straight away. Anything the lobby
     * does to a specific player goes through {@code Scheduling.entity} so it lands on that player's
     * thread under Folia; without this the task would simply be dropped into a mock and never run.
     */
    private static void runsItsOwnTasksImmediately(Player player) {
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, Consumer.class).accept(null);
            return null;
        });
        when(player.getScheduler()).thenReturn(scheduler);
    }

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
        // Every test in this file stubs Bukkit.getWorld("world") — kept as an explicit fixture value
        // rather than DEFAULT_WORLD_NAME, so a change to the shipped default does not ripple through
        // every test here.
        settings.set("world-name", "world");
    }

    private SpeedrunLobby lobby() {
        return new SpeedrunLobby(plugin, settings);
    }

    private SpeedrunLobby lobbyWithCountdown(SpeedrunCountdownLauncher launcher) {
        return new SpeedrunLobby(plugin, settings, launcher);
    }

    private SpeedrunLobby lobbyWithMessages(Messages messages) {
        // A launcher that finishes immediately, as if the countdown had reached zero — start() is
        // what actually wires up the onFinish announcement this is testing.
        return new SpeedrunLobby(plugin, settings,
                (participants, onComplete) -> onComplete.run(), messages);
    }

    @Test
    @DisplayName("a fresh lobby with no session is READY")
    void freshLobbyIsReady() {
        assertThat(lobby().state()).isEqualTo(SpeedrunLobbyState.READY);
    }

    @Nested
    @DisplayName("beginCountdown")
    class BeginCountdown {

        @Test
        @DisplayName("refuses without ever touching the launcher when there is no end condition")
        void refusesWithoutLaunchingWhenHopeless() {
            settings.set("advancement-key", "");
            settings.set("death-policy", "OFF");
            java.util.concurrent.atomic.AtomicBoolean launched = new java.util.concurrent.atomic.AtomicBoolean();
            SpeedrunLobby lobby = lobbyWithCountdown((participants, onComplete) -> launched.set(true));

            SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(Set.of(ALICE));

            assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.NO_END_CONDITION);
            assertThat(launched).isFalse();
        }

        @Test
        @DisplayName("reports COUNTDOWN once launched, before the launcher completes")
        void reportsCountdownWhileWaiting() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                // Never calls onComplete — simulates the countdown still ticking.
                SpeedrunLobby lobby = lobbyWithCountdown((participants, onComplete) -> { });

                SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(Set.of(ALICE));

                assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);
                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.COUNTDOWN);
                assertThat(lobby.session()).isEmpty();
            }
        }

        @Test
        @DisplayName("refuses a second press while one is already counting down")
        void refusesWhileCountingDown() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
                SpeedrunLobby lobby = lobbyWithCountdown(
                        (participants, onComplete) -> launches.incrementAndGet());

                lobby.beginCountdown(Set.of(ALICE));
                SpeedrunLobby.StartOutcome second = lobby.beginCountdown(Set.of(BOB));

                assertThat(second).isEqualTo(SpeedrunLobby.StartOutcome.NOT_READY);
                assertThat(launches.get()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("once the launcher completes, the run actually starts")
        void startsForRealOnceTheCountdownCompletes() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                // A fake that finishes immediately, as if the countdown had reached zero.
                SpeedrunLobby lobby = lobbyWithCountdown(
                        (participants, onComplete) -> onComplete.run());

                SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(Set.of(ALICE, BOB));

                assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);
                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.RUNNING);
                assertThat(lobby.session()).isPresent();
                assertThat(lobby.session().orElseThrow().participants())
                        .containsExactlyInAnyOrder(ALICE, BOB);
            }
        }

        @Test
        @DisplayName("without a launcher at all, refuses rather than throwing")
        void refusesGracefullyWithNoLauncher() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobby();   // the 2-arg constructor has no countdown launcher

                SpeedrunLobby.StartOutcome outcome = lobby.beginCountdown(Set.of(ALICE));

                assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.NOT_READY);
                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.READY);
            }
        }
    }

    @Nested
    @DisplayName("starting")
    class Starting {

        @Test
        @DisplayName("refuses when neither an advancement nor a death policy is configured")
        void refusesWithNoEndCondition() {
            settings.set("advancement-key", "");
            settings.set("death-policy", "OFF");
            SpeedrunLobby lobby = lobby();

            SpeedrunLobby.StartOutcome outcome = lobby.start(Set.of(ALICE));

            assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.NO_END_CONDITION);
            assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.READY);
        }

        @Test
        @DisplayName("refuses an empty roster")
        void refusesEmptyRoster() {
            SpeedrunLobby lobby = lobby();

            SpeedrunLobby.StartOutcome outcome = lobby.start(Set.of());

            assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.NO_PARTICIPANTS);
        }

        @Test
        @DisplayName("refuses when the configured world is not loaded")
        void refusesMissingWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
                SpeedrunLobby lobby = lobby();

                SpeedrunLobby.StartOutcome outcome = lobby.start(Set.of(ALICE));

                assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.WORLD_MISSING);
            }
        }

        @Test
        @DisplayName("succeeds and moves the lobby to RUNNING")
        void succeeds() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobby();

                SpeedrunLobby.StartOutcome outcome = lobby.start(Set.of(ALICE, BOB));

                assertThat(outcome).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);
                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.RUNNING);
                assertThat(lobby.session()).isPresent();
                assertThat(lobby.session().orElseThrow().participants()).containsExactlyInAnyOrder(ALICE, BOB);
            }
        }

        @Test
        @DisplayName("sets every participant's respawn point to wherever they are the moment the run begins")
        void setsRespawnPointToWhereTheRunBegan() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                Player alice = mock(Player.class);
                Location aliceAt = mock(Location.class);
                when(alice.getLocation()).thenReturn(aliceAt);
                runsItsOwnTasksImmediately(alice);
                bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
                SpeedrunLobby lobby = lobby();

                lobby.start(Set.of(ALICE));

                verify(alice).setRespawnLocation(aliceAt, true);
            }
        }

        @Test
        @DisplayName("arms a DragonExitEndCondition for the default dragon-kill goal, not a plain advancement one")
        void wiresTheExitPortalConditionForTheDragonKillGoal() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobby();

                lobby.start(Set.of(ALICE));

                org.mockito.ArgumentCaptor<org.bukkit.event.Listener> captor =
                        org.mockito.ArgumentCaptor.forClass(org.bukkit.event.Listener.class);
                verify(pluginManager, org.mockito.Mockito.atLeastOnce())
                        .registerEvents(captor.capture(), eq(plugin));
                assertThat(captor.getAllValues()).anyMatch(
                        listener -> listener instanceof de.raindancer.modules.speedrun.conditions.DragonExitEndCondition);
                assertThat(captor.getAllValues()).noneMatch(
                        listener -> listener instanceof de.raindancer.modules.speedrun.conditions.AdvancementEndCondition);
            }
        }

        @Test
        @DisplayName("arms a plain AdvancementEndCondition once the exit-portal requirement is turned off")
        void wiresThePlainAdvancementConditionWhenThePortalIsNotRequired() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                settings.set("require-exit-portal-after-dragon", "false");
                SpeedrunLobby lobby = lobby();

                lobby.start(Set.of(ALICE));

                org.mockito.ArgumentCaptor<org.bukkit.event.Listener> captor =
                        org.mockito.ArgumentCaptor.forClass(org.bukkit.event.Listener.class);
                verify(pluginManager, org.mockito.Mockito.atLeastOnce())
                        .registerEvents(captor.capture(), eq(plugin));
                assertThat(captor.getAllValues()).anyMatch(
                        listener -> listener instanceof de.raindancer.modules.speedrun.conditions.AdvancementEndCondition);
                assertThat(captor.getAllValues()).noneMatch(
                        listener -> listener instanceof de.raindancer.modules.speedrun.conditions.DragonExitEndCondition);
            }
        }

        @Test
        @DisplayName("starts the action-bar clock for the fresh session")
        void startsTheActionBarClock() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                Map<UUID, Component> shown = new HashMap<>();
                ActionBarSink sink = (player, message) -> shown.put(player, message);
                SpeedrunTimerDisplay.Ticker noopTicker = task -> () -> { };
                SpeedrunTimerDisplay display = new SpeedrunTimerDisplay(
                        new ActionBars(sink, () -> 0L), noopTicker);
                SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings,
                        (participants, onComplete) -> onComplete.run(), display);

                lobby.start(Set.of(ALICE));

                assertThat(shown).containsKey(ALICE);
            }
        }

        @Test
        @DisplayName("resets every participant and the world before the clock starts")
        void resetsStandardConditionsOnStart() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                when(world.getEntities()).thenReturn(List.of());
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
                de.raindancer.core.moderation.players.PlayerAdmin players =
                        mock(de.raindancer.core.moderation.players.PlayerAdmin.class);
                SpeedrunPreparation preparation = new SpeedrunPreparation(players);
                SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings,
                        (participants, onComplete) -> onComplete.run(), preparation);

                lobby.start(Set.of(ALICE));

                verify(players).heal(ALICE);
                verify(world).setTime(SpeedrunPreparation.DAY_START);
            }
        }

        @Test
        @DisplayName("refuses a second start while a run is already under way")
        void refusesWhileAlreadyRunning() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobby();
                lobby.start(Set.of(ALICE));

                SpeedrunLobby.StartOutcome second = lobby.start(Set.of(BOB));

                assertThat(second).isEqualTo(SpeedrunLobby.StartOutcome.NOT_READY);
                assertThat(lobby.session().orElseThrow().participants()).containsExactly(ALICE);
            }
        }
    }

    @Nested
    @DisplayName("announcing a finish")
    class AnnouncingFinish {

        @Test
        @DisplayName("tells every online participant the reason and the time, in chat")
        void tellsOnlineParticipants() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                Player alice = mock(Player.class);
                runsItsOwnTasksImmediately(alice);
                bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
                bukkit.when(() -> Bukkit.getPlayer(BOB)).thenReturn(null);   // Bob already left
                Messages messages = mock(Messages.class);
                SpeedrunLobby lobby = lobbyWithMessages(messages);

                lobby.beginCountdown(Set.of(ALICE, BOB));
                lobby.session().orElseThrow().finish("advancement:minecraft:end/kill_dragon");

                verify(messages).send(eq(alice), eq("speedrun.finished"),
                        eq("reason"), any(), eq("time"), any());
            }
        }

        @Test
        @DisplayName("a lobby built without Messages does not throw when a run finishes")
        void doesNotThrowWithoutMessages() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobby();
                lobby.start(Set.of(ALICE));

                assertThatCode(() -> lobby.session().orElseThrow().finish("done"))
                        .doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("auto-reset once a finished run is abandoned")
    class AutoReset {

        @Test
        @DisplayName("does nothing while a participant is still online")
        void doesNothingWhileSomebodyRemains() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
                SpeedrunLobby lobby = lobby();
                lobby.start(Set.of(ALICE, BOB));
                lobby.session().orElseThrow().finish("done");
                bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(null);
                bukkit.when(() -> Bukkit.getPlayer(BOB)).thenReturn(mock(org.bukkit.entity.Player.class));

                lobby.resetIfAbandoned(ALICE);

                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.FINISHED);
            }
        }

        @Test
        @DisplayName("regenerates the world and returns to READY once the last participant leaves")
        void resetsOnceEverybodyIsGone() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class,
                         (mockCreator, context) -> when(mockCreator.createWorld())
                                 .thenReturn(mock(World.class)))) {
                World world = mock(World.class);
                World mainWorld = mock(World.class);
                Location spawn = mock(Location.class);
                when(mainWorld.getSpawnLocation()).thenReturn(spawn);
                when(world.getName()).thenReturn("world");
                when(world.getWorldFolder()).thenReturn(dataFolder.resolve("speedrun").toFile());
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
                bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mainWorld));
                bukkit.when(() -> Bukkit.unloadWorld(world, false)).thenReturn(true);
                io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler globalScheduler =
                        mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
                bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
                org.mockito.stubbing.Answer<Void> runImmediately = invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return null;
                };
                org.mockito.Mockito.doAnswer(runImmediately).when(globalScheduler).execute(eq(plugin), any(Runnable.class));

                SpeedrunLobby lobby = lobby();
                lobby.start(Set.of(ALICE));
                lobby.session().orElseThrow().finish("done");
                bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(null);

                lobby.resetIfAbandoned(ALICE);

                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.READY);
                assertThat(lobby.session()).isEmpty();
            }
        }

        @Test
        @DisplayName("does nothing while the lobby is not FINISHED")
        void doesNothingWhenNotFinished() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobby();
                lobby.start(Set.of(ALICE));

                lobby.resetIfAbandoned(ALICE);

                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.RUNNING);
            }
        }
    }

    @Nested
    @DisplayName("/lemmemove releasing a player from the movement freeze")
    class Release {

        @Test
        @DisplayName("nobody is released by default")
        void nobodyIsReleasedByDefault() {
            assertThat(lobby().isReleased(ALICE)).isFalse();
        }

        @Test
        @DisplayName("release() exempts exactly the player named")
        void releaseExemptsOnlyThatPlayer() {
            SpeedrunLobby lobby = lobby();

            lobby.release(ALICE);

            assertThat(lobby.isReleased(ALICE)).isTrue();
            assertThat(lobby.isReleased(BOB)).isFalse();
        }
    }

    @Nested
    @DisplayName("/speedrunspectate toggling non-runner status")
    class Spectators {

        @Test
        @DisplayName("nobody is a spectator by default")
        void nobodyIsASpectatorByDefault() {
            assertThat(lobby().isSpectator(ALICE)).isFalse();
        }

        @Test
        @DisplayName("toggling flips the state and returns the new one")
        void togglingFlipsState() {
            SpeedrunLobby lobby = lobby();

            boolean firstToggle = lobby.toggleSpectator(ALICE);
            assertThat(firstToggle).isTrue();
            assertThat(lobby.isSpectator(ALICE)).isTrue();

            boolean secondToggle = lobby.toggleSpectator(ALICE);
            assertThat(secondToggle).isFalse();
            assertThat(lobby.isSpectator(ALICE)).isFalse();
        }
    }

    @Nested
    @DisplayName("/starthere and teleporting to it")
    class StartPoint {

        @Test
        @DisplayName("setStartPoint writes every field and turns the flag on")
        void setStartPointWritesEveryField() {
            SpeedrunLobby lobby = lobby();
            World world = mock(World.class);
            Location point = new Location(world, 12.5, 70, -3.5, 90f, 15f);

            lobby.setStartPoint(point);

            SpeedrunSettings config = lobby.config();
            assertThat(config.startPointSet()).isTrue();
            assertThat(config.startX()).isEqualTo(12.5);
            assertThat(config.startY()).isEqualTo(70);
            assertThat(config.startZ()).isEqualTo(-3.5);
            assertThat(config.startYaw()).isEqualTo(90);
            assertThat(config.startPitch()).isEqualTo(15);
        }

        @Test
        @DisplayName("beginCountdown teleports every participant there once a point is set")
        void teleportsParticipantsOnceSet() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                World world = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
                settings.set("start-point-set", "true");
                settings.set("start-x", "5");
                settings.set("start-y", "70");
                settings.set("start-z", "5");
                Player alice = mock(Player.class);
                bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
                SpeedrunLobby lobby = lobbyWithCountdown((participants, onComplete) -> { });

                lobby.beginCountdown(Set.of(ALICE));

                org.mockito.ArgumentCaptor<Location> captor = org.mockito.ArgumentCaptor.forClass(Location.class);
                verify(alice).teleportAsync(captor.capture());
                assertThat(captor.getValue().getX()).isEqualTo(5.0);
                assertThat(captor.getValue().getY()).isEqualTo(70.0);
                assertThat(captor.getValue().getZ()).isEqualTo(5.0);
            }
        }

        @Test
        @DisplayName("does not teleport anybody when no start point is set")
        void doesNotTeleportWithoutAPoint() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                Player alice = mock(Player.class);
                bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
                SpeedrunLobby lobby = lobbyWithCountdown((participants, onComplete) -> { });

                lobby.beginCountdown(Set.of(ALICE));

                verify(alice, org.mockito.Mockito.never()).teleportAsync(any(Location.class));
            }
        }
    }

    @Nested
    @DisplayName("forceReset — the admin escape hatch")
    class ForceReset {

        @Test
        @DisplayName("wipes and remakes the world even while READY, with nothing running")
        void resetsAnUntouchedReadyWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class,
                         (mockCreator, context) -> when(mockCreator.createWorld())
                                 .thenReturn(mock(World.class)))) {
                World world = mock(World.class);
                World mainWorld = mock(World.class);
                Location spawn = mock(Location.class);
                when(mainWorld.getSpawnLocation()).thenReturn(spawn);
                when(world.getName()).thenReturn("world");
                when(world.getWorldFolder()).thenReturn(dataFolder.resolve("speedrun").toFile());
                when(world.getPlayers()).thenReturn(List.of());
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
                bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mainWorld));
                bukkit.when(() -> Bukkit.unloadWorld(world, false)).thenReturn(true);
                io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler globalScheduler =
                        mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
                bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
                org.mockito.stubbing.Answer<Void> runImmediately = invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return null;
                };
                org.mockito.Mockito.doAnswer(runImmediately).when(globalScheduler).execute(eq(plugin), any(Runnable.class));

                SpeedrunLobby.ResetOutcome outcome = lobby().forceReset();

                assertThat(outcome).isEqualTo(SpeedrunLobby.ResetOutcome.RESET);
                bukkit.verify(() -> Bukkit.unloadWorld(world, false));
            }
        }

        @Test
        @DisplayName("refuses mid-countdown rather than racing the launcher's own callback")
        void refusesMidCountdown() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                SpeedrunLobby lobby = lobbyWithCountdown((participants, onComplete) -> { });
                lobby.beginCountdown(Set.of(ALICE));

                assertThat(lobby.forceReset()).isEqualTo(SpeedrunLobby.ResetOutcome.COUNTDOWN_IN_PROGRESS);
                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.COUNTDOWN);
            }
        }

        @Test
        @DisplayName("ends a running session and regenerates the world, evacuating whoever is standing in it")
        void resetsARunningSession() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class,
                         (mockCreator, context) -> when(mockCreator.createWorld())
                                 .thenReturn(mock(World.class)))) {
                World world = mock(World.class);
                World mainWorld = mock(World.class);
                Location spawn = mock(Location.class);
                when(mainWorld.getSpawnLocation()).thenReturn(spawn);
                when(world.getName()).thenReturn("world");
                when(world.getWorldFolder()).thenReturn(dataFolder.resolve("speedrun").toFile());
                when(world.getPlayers()).thenReturn(List.of());
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
                bukkit.when(Bukkit::getWorlds).thenReturn(List.of(mainWorld));
                bukkit.when(() -> Bukkit.unloadWorld(world, false)).thenReturn(true);
                io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler globalScheduler =
                        mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
                bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
                org.mockito.stubbing.Answer<Void> runImmediately = invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return null;
                };
                org.mockito.Mockito.doAnswer(runImmediately).when(globalScheduler).execute(eq(plugin), any(Runnable.class));

                SpeedrunLobby lobby = lobby();
                lobby.start(Set.of(ALICE));

                SpeedrunLobby.ResetOutcome outcome = lobby.forceReset();

                assertThat(outcome).isEqualTo(SpeedrunLobby.ResetOutcome.RESET);
                assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.READY);
                assertThat(lobby.session()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("ensureWorldExists — making sure there is a world to race in at all")
    class EnsureWorldExists {

        private MockedConstruction<WorldCreator> creatorsMakingWorlds() {
            return mockConstruction(WorldCreator.class, (creator, context) -> {
                when(creator.environment(any())).thenReturn(creator);
                when(creator.createWorld()).thenReturn(mock(World.class));
            });
        }

        /**
         * All three, not just the one: a world made at runtime has no dimensions of its own, and
         * without them a nether portal drops the racer into the server's nether — and walking back
         * out of that put them in the server's overworld, outside the race.
         */
        @Test
        @DisplayName("creates the configured world and both of its dimensions when none are loaded")
        void createsTheWholeGroupWhenMissing() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMakingWorlds()) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(null);
                bukkit.when(() -> Bukkit.getWorld("world_nether")).thenReturn(null);
                bukkit.when(() -> Bukkit.getWorld("world_the_end")).thenReturn(null);

                lobby().ensureWorldExists();

                assertThat(creators.constructed()).hasSize(3);
                verify(creators.constructed().get(1)).environment(World.Environment.NETHER);
                verify(creators.constructed().get(2)).environment(World.Environment.THE_END);
            }
        }

        @Test
        @DisplayName("creates only the dimensions that are missing beside an already-loaded world")
        void fillsInWhatIsMissing() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = creatorsMakingWorlds()) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                bukkit.when(() -> Bukkit.getWorld("world_nether")).thenReturn(mock(World.class));
                bukkit.when(() -> Bukkit.getWorld("world_the_end")).thenReturn(null);

                lobby().ensureWorldExists();

                assertThat(creators.constructed()).hasSize(1);
                verify(creators.constructed().getFirst()).environment(World.Environment.THE_END);
            }
        }

        @Test
        @DisplayName("does nothing at all when the world and both its dimensions are loaded")
        void doesNothingWhenAlreadyLoaded() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class)) {
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(mock(World.class));
                bukkit.when(() -> Bukkit.getWorld("world_nether")).thenReturn(mock(World.class));
                bukkit.when(() -> Bukkit.getWorld("world_the_end")).thenReturn(mock(World.class));

                lobby().ensureWorldExists();

                assertThat(creators.constructed()).isEmpty();
            }
        }

        @Test
        @DisplayName("does not try to create the world when it is already loaded as the primary world")
        void doesNotTryToCreateAnAlreadyLoadedPrimaryWorld() {
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                 MockedConstruction<WorldCreator> creators = mockConstruction(WorldCreator.class)) {
                World primary = mock(World.class);
                bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(primary);
                bukkit.when(() -> Bukkit.getWorld("world_nether")).thenReturn(mock(World.class));
                bukkit.when(() -> Bukkit.getWorld("world_the_end")).thenReturn(mock(World.class));
                bukkit.when(Bukkit::getWorlds).thenReturn(List.of(primary));

                assertThatCode(() -> lobby().ensureWorldExists()).doesNotThrowAnyException();

                assertThat(creators.constructed()).isEmpty();
            }
        }
    }
}
