package de.raindancer.modules.manhunt.service;

import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.conditions.AllRunnersDeadEndCondition;
import de.raindancer.modules.manhunt.conditions.RunnerExitEndCondition;
import de.raindancer.modules.manhunt.conditions.TimeoutEndCondition;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.speedrun.SpeedrunOccupancyListener;
import de.raindancer.modules.speedrun.SpeedrunReset;
import de.raindancer.modules.speedrun.SpeedrunSeed;
import de.raindancer.modules.speedrun.SpeedrunSession;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Starting, stopping and resetting — with the ticker replaced by {@link ManhuntService#manual()},
 * following {@code ChainServiceTest}'s own pattern.
 */
@ExtendWith(MockitoExtension.class)
class ManhuntServiceTest {

    private final UUID runner = UUID.randomUUID();
    private final UUID hunter = UUID.randomUUID();

    private Plugin plugin;
    private Server server;
    private PluginManager pluginManager;
    private ManhuntTeams teams;
    private BossBars bossBars;
    private Messages messages;
    private SpeedrunReset reset;

    /** Plain settings that touch neither the global scheduler (no reset-on-start, no head start) nor
     *  Bukkit's advancement API, so most tests need no {@code mockStatic(Bukkit.class)} at all. */
    private static final ManhuntSettings PLAIN = ManhuntSettings.DEFAULTS
            .withResetOnStart(false)
            .withHunterReleaseDelaySeconds(0);

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        lenient().when(plugin.getServer()).thenReturn(server);
        lenient().when(server.getPluginManager()).thenReturn(pluginManager);
        lenient().when(server.getWorld(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(mock(World.class));

        teams = new ManhuntTeams(() -> false);
        bossBars = mock(BossBars.class);
        messages = mock(Messages.class);
        reset = mock(SpeedrunReset.class);
    }

    private ManhuntService service(ManhuntSettings settings) {
        return new ManhuntService(plugin, teams, bossBars, messages, reset, ManhuntService.manual(), settings);
    }

    @Nested
    @DisplayName("starting a run")
    class Starting {

        @Test
        @DisplayName("with nobody on either side, starting is refused")
        void emptyRosterRefuses() {
            ManhuntService service = service(PLAIN);

            assertThat(service.start()).isEqualTo(ManhuntService.StartOutcome.NO_RUNNERS);
        }

        @Test
        @DisplayName("with a Runner but no Hunter, starting is refused")
        void noHunterRefuses() {
            teams.joinRunners(runner);
            ManhuntService service = service(PLAIN);

            assertThat(service.start()).isEqualTo(ManhuntService.StartOutcome.NO_HUNTERS);
        }

        @Test
        @DisplayName("with a Hunter but no Runner, starting is refused")
        void noRunnerRefuses() {
            teams.joinHunters(hunter);
            ManhuntService service = service(PLAIN);

            assertThat(service.start()).isEqualTo(ManhuntService.StartOutcome.NO_RUNNERS);
        }

        @Test
        @DisplayName("wires the default win conditions and the occupancy watcher")
        void wiresDefaultConditions() {
            teams.joinRunners(runner);
            teams.joinHunters(hunter);
            ManhuntService service = service(PLAIN);

            assertThat(service.start()).isEqualTo(ManhuntService.StartOutcome.STARTED);
            assertThat(service.isRunning()).isTrue();

            ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
            verify(pluginManager, times(3)).registerEvents(captor.capture(), eq(plugin));
            assertThat(captor.getAllValues())
                    .anyMatch(RunnerExitEndCondition.class::isInstance)
                    .anyMatch(AllRunnersDeadEndCondition.class::isInstance)
                    .anyMatch(SpeedrunOccupancyListener.class::isInstance);
        }

        @Test
        @DisplayName("a TIMEOUT hunter win condition arms a timer instead of a death watcher")
        void wiresTimeoutCondition() {
            teams.joinRunners(runner);
            teams.joinHunters(hunter);
            ManhuntSettings settings = PLAIN
                    .withHunterWin(ManhuntSettings.HunterWinCondition.TIMEOUT)
                    .withHunterTimeoutMinutes(5);
            ManhuntService service = service(settings);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler scheduler =
                        mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
                bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);

                assertThat(service.start()).isEqualTo(ManhuntService.StartOutcome.STARTED);
            }

            Optional<SpeedrunSession> session = service.session();
            assertThat(session).isPresent();
        }

        @Test
        @DisplayName("starting twice while a run is going is refused the second time")
        void doubleStartIsRefused() {
            teams.joinRunners(runner);
            teams.joinHunters(hunter);
            ManhuntService service = service(PLAIN);

            assertThat(service.start()).isEqualTo(ManhuntService.StartOutcome.STARTED);
            assertThat(service.start()).isEqualTo(ManhuntService.StartOutcome.ALREADY_RUNNING);
        }

        @Test
        @DisplayName("with the configured world not loaded, starting is refused")
        void missingWorldRefuses() {
            teams.joinRunners(runner);
            teams.joinHunters(hunter);
            when(server.getWorld("world")).thenReturn(null);
            ManhuntService service = service(PLAIN);

            assertThat(service.start()).isEqualTo(ManhuntService.StartOutcome.WORLD_MISSING);
        }
    }

    @Nested
    @DisplayName("resetting on start")
    class ResettingOnStart {

        @Test
        @DisplayName("off by default in these tests' own PLAIN settings, nothing is reset")
        void offDoesNothing() {
            teams.joinRunners(runner);
            teams.joinHunters(hunter);
            ManhuntService service = service(PLAIN);

            service.start();

            verify(reset, never()).regenerate(any(), any(), any());
        }

        @Test
        @DisplayName("on, resets with the configured fixed seed")
        void fixedSeedIsUsed() {
            teams.joinRunners(runner);
            teams.joinHunters(hunter);
            World world = server.getWorld("world");
            ManhuntSettings settings = PLAIN.withResetOnStart(true)
                    .withSeedChoice(ManhuntSettings.SeedChoice.FIXED)
                    .withSeedValue(99L);
            ManhuntService service = service(settings);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubGlobalScheduler(bukkit);
                service.start();
            }

            ArgumentCaptor<SpeedrunSeed> seedCaptor = ArgumentCaptor.forClass(SpeedrunSeed.class);
            verify(reset).regenerate(eq(world), seedCaptor.capture(), eq(java.util.Set.of(runner, hunter)));
            assertThat(seedCaptor.getValue()).isEqualTo(SpeedrunSeed.fixed(99L));
        }

        private void stubGlobalScheduler(MockedStatic<Bukkit> bukkit) {
            io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler scheduler =
                    mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            org.mockito.Mockito.doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(1)).run();
                return null;
            }).when(scheduler).execute(eq(plugin), any(Runnable.class));
        }
    }

    @Nested
    @DisplayName("stopping")
    class Stopping {

        @Test
        @DisplayName("stopping a run that is going finishes it")
        void stopsARunningHunt() {
            teams.joinRunners(runner);
            teams.joinHunters(hunter);
            ManhuntService service = service(PLAIN);
            service.start();

            // finish() tells everybody still online, which asks Bukkit.getPlayer(id) — unstubbed,
            // an empty static mock answers null for that and the announce loop simply skips them.
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                assertThat(service.stop()).isTrue();
            }
            assertThat(service.isRunning()).isFalse();
        }

        @Test
        @DisplayName("stopping when nothing is running is refused")
        void stoppingNothingIsRefused() {
            ManhuntService service = service(PLAIN);

            assertThat(service.stop()).isFalse();
        }
    }

    @Nested
    @DisplayName("hooks, for something else to wire up without touching this class' constructors")
    class Hooks {

        @Test
        @DisplayName("onStart fires with the full roster right as a run starts")
        void onStartFiresWithTheRoster() {
            teams.joinRunners(runner);
            teams.joinHunters(hunter);
            ManhuntService service = service(PLAIN);
            java.util.concurrent.atomic.AtomicReference<java.util.Set<UUID>> seen =
                    new java.util.concurrent.atomic.AtomicReference<>();
            service.onStart(seen::set);

            assertThat(service.start()).isEqualTo(ManhuntService.StartOutcome.STARTED);

            assertThat(seen.get()).isEqualTo(java.util.Set.of(runner, hunter));
        }

        @Test
        @DisplayName("onStart does not fire when starting is refused")
        void onStartDoesNotFireWhenRefused() {
            ManhuntService service = service(PLAIN);
            java.util.concurrent.atomic.AtomicReference<java.util.Set<UUID>> seen =
                    new java.util.concurrent.atomic.AtomicReference<>();
            service.onStart(seen::set);

            assertThat(service.start()).isEqualTo(ManhuntService.StartOutcome.NO_RUNNERS);

            assertThat(seen.get()).isNull();
        }

        @Test
        @DisplayName("onFinished fires with the roster and the outcome once the run finishes")
        void onFinishedFiresWithRosterAndOutcome() {
            teams.joinRunners(runner);
            teams.joinHunters(hunter);
            ManhuntService service = service(PLAIN);
            java.util.concurrent.atomic.AtomicReference<java.util.Set<UUID>> seenRoster =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<de.raindancer.modules.speedrun.SpeedrunOutcome> seenOutcome =
                    new java.util.concurrent.atomic.AtomicReference<>();
            service.onFinished((roster, outcome) -> {
                seenRoster.set(roster);
                seenOutcome.set(outcome);
            });
            service.start();

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                assertThat(service.stop()).isTrue();
            }

            assertThat(seenRoster.get()).isEqualTo(java.util.Set.of(runner, hunter));
            assertThat(seenOutcome.get()).isNotNull();
            assertThat(seenOutcome.get().reason()).isEqualTo("manual");
        }
    }
}
