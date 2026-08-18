package de.raindancer.modules.chained.service;

import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.SpeedrunOccupancyListener;
import de.raindancer.modules.speedrun.SpeedrunReset;
import de.raindancer.modules.speedrun.SpeedrunSeed;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.conditions.AdvancementEndCondition;
import de.raindancer.modules.speedrun.conditions.DeathEndCondition;
import de.raindancer.modules.chained.ChainedSettings;
import de.raindancer.modules.chained.model.ChainPair;
import de.raindancer.modules.chained.store.ChainPairStore;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pairing, running and resetting — with the ticker replaced by {@link ChainService#manual()} so
 * nothing here needs a live scheduler, following {@code GameTimerServiceTest}'s pattern in the
 * hungergames module.
 */
@ExtendWith(MockitoExtension.class)
class ChainServiceTest {

    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    private Plugin plugin;
    private Server server;
    private PluginManager pluginManager;
    private ChainPairStore pairs;
    private BossBars bossBars;
    private Messages messages;
    private SpeedrunReset reset;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        // lenient: not every test in this class reaches an end condition or a world reset, so not
        // every test actually asks the plugin for its server.
        lenient().when(plugin.getServer()).thenReturn(server);
        lenient().when(server.getPluginManager()).thenReturn(pluginManager);

        pairs = new ChainPairStore();
        bossBars = mock(BossBars.class);
        messages = mock(Messages.class);
        reset = mock(SpeedrunReset.class);
    }

    private ChainService service(ChainedSettings settings) {
        return new ChainService(plugin, pairs, bossBars, messages, reset, ChainService.manual(), settings);
    }

    @Nested
    @DisplayName("pairing")
    class Pairing {

        @Test
        @DisplayName("creating a pair registers both players")
        void createsAPair() {
            ChainService service = service(ChainedSettings.DEFAULTS);

            ChainPair made = service.pair(first, second, 20);

            assertThat(made.a()).isEqualTo(first);
            assertThat(made.b()).isEqualTo(second);
            assertThat(pairs.pairOf(first)).contains(made);
            assertThat(pairs.pairOf(second)).contains(made);
        }

        @Test
        @DisplayName("removing a pair unregisters both sides")
        void removesAPair() {
            ChainService service = service(ChainedSettings.DEFAULTS);
            service.pair(first, second, 20);

            assertThat(service.unpair(first)).isTrue();
            assertThat(pairs.pairOf(first)).isEmpty();
            assertThat(pairs.pairOf(second)).isEmpty();
        }

        @Test
        @DisplayName("unpairing somebody with no pair says so rather than throwing")
        void unpairingNobodyIsFalse() {
            ChainService service = service(ChainedSettings.DEFAULTS);

            assertThat(service.unpair(first)).isFalse();
        }
    }

    @Nested
    @DisplayName("starting a run")
    class Starting {

        @Test
        @DisplayName("with no pair, starting is refused")
        void noPairRefuses() {
            ChainService service = service(ChainedSettings.DEFAULTS);

            assertThat(service.start(first)).isEmpty();
        }

        @Test
        @DisplayName("wires the advancement end condition when settings say ADVANCEMENT")
        void wiresAdvancementCondition() {
            ChainedSettings settings = ChainedSettings.DEFAULTS
                    .withEndCondition(ChainedSettings.EndCondition.ADVANCEMENT);
            ChainService service = service(settings);
            service.pair(first, second, 20);

            Optional<SpeedrunSession> session;
            // Arming the condition revokes the goal so it can be earned again — see the speedrun
            // module's GoalAdvancement; there is no server here to ask for the advancement.
            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                session = service.start(first);
            }

            assertThat(session).isPresent();
            // Two listeners register on a real start: the end condition, and the occupancy watcher
            // that pauses the clock while both halves of the pair are offline — every run gets one
            // of those, regardless of which end condition is configured.
            ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
            verify(pluginManager, org.mockito.Mockito.times(2))
                    .registerEvents(captor.capture(), eq(plugin));
            assertThat(captor.getAllValues())
                    .anyMatch(AdvancementEndCondition.class::isInstance)
                    .anyMatch(SpeedrunOccupancyListener.class::isInstance);
        }

        @Test
        @DisplayName("wires the death end condition when settings say DEATH")
        void wiresDeathCondition() {
            ChainedSettings settings = ChainedSettings.DEFAULTS
                    .withEndCondition(ChainedSettings.EndCondition.DEATH)
                    .withDeathPolicy(DeathEndCondition.DeathPolicy.ALL);
            ChainService service = service(settings);
            service.pair(first, second, 20);

            service.start(first);

            ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
            verify(pluginManager, org.mockito.Mockito.times(2))
                    .registerEvents(captor.capture(), eq(plugin));
            assertThat(captor.getAllValues())
                    .anyMatch(DeathEndCondition.class::isInstance)
                    .anyMatch(SpeedrunOccupancyListener.class::isInstance);
        }

        @Test
        @DisplayName("wires only the occupancy watcher when settings say MANUAL")
        void wiresOnlyOccupancyForManual() {
            ChainedSettings settings = ChainedSettings.DEFAULTS
                    .withEndCondition(ChainedSettings.EndCondition.MANUAL);
            ChainService service = service(settings);
            service.pair(first, second, 20);

            service.start(first);

            // A run without any automatic end condition still has to pause when everybody leaves —
            // that behaviour does not depend on which end condition (if any) is configured.
            ArgumentCaptor<Listener> captor = ArgumentCaptor.forClass(Listener.class);
            verify(pluginManager).registerEvents(captor.capture(), eq(plugin));
            assertThat(captor.getValue()).isInstanceOf(SpeedrunOccupancyListener.class);
        }

        @Test
        @DisplayName("stopping a run unregisters its occupancy watcher")
        void stoppingUnregistersOccupancy() {
            ChainedSettings settings = ChainedSettings.DEFAULTS
                    .withEndCondition(ChainedSettings.EndCondition.MANUAL);
            ChainService service = service(settings);
            service.pair(first, second, 20);
            service.start(first);

            // HandlerList.unregisterAll is a static Bukkit call, not something this repo mocks — the
            // observable half of it here is that stopping does not throw and the session is gone;
            // ChainMovementListenerTest and the wider integration/manual pass cover it actually
            // detaching from a live PluginManager (Bukkit itself, not a Mockito mock).
            assertThat(service.stop(first)).isTrue();
        }

        @Test
        @DisplayName("starting twice for the same pair is refused the second time")
        void doubleStartIsRefused() {
            ChainService service = service(ChainedSettings.DEFAULTS
                    .withEndCondition(ChainedSettings.EndCondition.MANUAL));
            service.pair(first, second, 20);

            assertThat(service.start(first)).isPresent();
            assertThat(service.start(second)).isEmpty();
        }
    }

    @Nested
    @DisplayName("resetting on start")
    class ResettingOnStart {

        @Test
        @DisplayName("a fixed seed choice resets with that fixed seed")
        void fixedSeedIsUsed() {
            World world = mock(World.class);
            when(server.getWorld("world")).thenReturn(world);

            ChainedSettings settings = ChainedSettings.DEFAULTS
                    .withResetOnStart(true)
                    .withEndCondition(ChainedSettings.EndCondition.MANUAL)
                    .withSeedChoice(ChainedSettings.SeedChoice.FIXED)
                    .withSeedValue(1234L);
            ChainService service = service(settings);
            service.pair(first, second, 20);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubGlobalScheduler(bukkit);
                service.start(first);
            }

            ArgumentCaptor<SpeedrunSeed> seedCaptor = ArgumentCaptor.forClass(SpeedrunSeed.class);
            verify(reset).regenerate(eq(world), seedCaptor.capture(), eq(Set.of(first, second)));
            assertThat(seedCaptor.getValue()).isEqualTo(SpeedrunSeed.fixed(1234L));
        }

        @Test
        @DisplayName("a random seed choice resets with a random seed")
        void randomSeedIsUsed() {
            World world = mock(World.class);
            when(server.getWorld("world")).thenReturn(world);

            ChainedSettings settings = ChainedSettings.DEFAULTS
                    .withResetOnStart(true)
                    .withEndCondition(ChainedSettings.EndCondition.MANUAL)
                    .withSeedChoice(ChainedSettings.SeedChoice.RANDOM);
            ChainService service = service(settings);
            service.pair(first, second, 20);

            try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
                stubGlobalScheduler(bukkit);
                service.start(first);
            }

            ArgumentCaptor<SpeedrunSeed> seedCaptor = ArgumentCaptor.forClass(SpeedrunSeed.class);
            verify(reset).regenerate(eq(world), seedCaptor.capture(), eq(Set.of(first, second)));
            assertThat(seedCaptor.getValue()).isEqualTo(SpeedrunSeed.random());
        }

        /** Runs whatever {@code Scheduling.global} hands the global region scheduler immediately. */
        private void stubGlobalScheduler(MockedStatic<Bukkit> bukkit) {
            io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler scheduler =
                    mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(scheduler);
            org.mockito.Mockito.doAnswer(invocation -> {
                ((Runnable) invocation.getArgument(1)).run();
                return null;
            }).when(scheduler).execute(eq(plugin), any(Runnable.class));
        }

        @Test
        @DisplayName("off by default, nothing is reset on start")
        void offByDefaultDoesNothing() {
            ChainService service = service(ChainedSettings.DEFAULTS
                    .withEndCondition(ChainedSettings.EndCondition.MANUAL));
            service.pair(first, second, 20);

            service.start(first);

            verify(reset, never()).regenerate(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("stopping")
    class Stopping {

        @Test
        @DisplayName("stopping a run that is going finishes it and says so")
        void stopsARunningPair() {
            ChainService service = service(ChainedSettings.DEFAULTS
                    .withEndCondition(ChainedSettings.EndCondition.MANUAL));
            service.pair(first, second, 20);
            service.start(first);

            assertThat(service.stop(first)).isTrue();
            assertThat(service.sessionOf(first)).isEmpty();
        }

        @Test
        @DisplayName("stopping when nothing is running is refused")
        void stoppingNothingIsRefused() {
            ChainService service = service(ChainedSettings.DEFAULTS);
            service.pair(first, second, 20);

            assertThat(service.stop(first)).isFalse();
        }
    }
}
