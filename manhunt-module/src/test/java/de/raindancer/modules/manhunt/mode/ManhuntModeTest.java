package de.raindancer.modules.manhunt.mode;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.service.Eliminations;
import de.raindancer.modules.manhunt.service.HuntDeathListener;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.manhunt.tracker.PortalMemory;
import de.raindancer.modules.manhunt.tracker.TrackerCompass;
import de.raindancer.modules.manhunt.tracker.TrackerCompassService;
import de.raindancer.modules.speedrun.SpeedrunOutcome;
import de.raindancer.modules.speedrun.SpeedrunRun;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunSettings;
import de.raindancer.modules.speedrun.SpeedrunWorlds;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Manhunt as the speedrun lobby sees it: what it refuses, what it builds when a run starts, and what
 * it gives back when one ends.
 *
 * <p>The lobby's own half of this is pinned in {@code SpeedrunLobbyModeTest} over in speedrun-module;
 * this is the other side of the same seam.
 */
class ManhuntModeTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());
    private static final UUID CARO = UUID.nameUUIDFromBytes("caro".getBytes());

    private Plugin plugin;
    private PluginManager pluginManager;
    private ManhuntTeams teams;
    private ManhuntWhitelistService whitelist;
    private TrackerCompassService tracker;
    private ManhuntMode mode;
    private AtomicReference<ManhuntSettings> settings;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(plugin.getName()).thenReturn("manhunt");
        when(plugin.namespace()).thenReturn("manhunt");

        teams = new ManhuntTeams(() -> false);
        whitelist = mock(ManhuntWhitelistService.class);
        PortalMemory portals = new PortalMemory();
        tracker = mock(TrackerCompassService.class);
        settings = new AtomicReference<>(ManhuntSettings.DEFAULTS);
        mode = new ManhuntMode(plugin, teams, mock(Eliminations.class), tracker, portals, whitelist,
                mock(Messages.class), settings::get, null);
    }

    /** A run the lobby would have handed the mode, built without a lobby. */
    private SpeedrunRun runWith(Set<UUID> participants) {
        return new SpeedrunRun(plugin, new SpeedrunSession(participants),
                SpeedrunWorlds.around("speedrun"));
    }

    private static SpeedrunSettings withGoal(boolean goal) {
        return new SpeedrunSettings("manhunt", "speedrun",
                goal ? SpeedrunSettings.DRAGON_KILL_ADVANCEMENT : "",
                true, de.raindancer.modules.speedrun.SpeedrunDeathPolicy.OFF, true,
                0, 0, 0, 0, false, 0, 0, 0, 0, 0,
                true, true, true, true, true, 10, true, 1000,
                true, true, true, true, true, true, true, true, true, true,
                false, false);
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusing {

        @Test
        @DisplayName("nobody running is the refusal, in the hunt's own words")
        void noRunner() {
            assertThat(mode.refuseStart(withGoal(true), Set.of(ANNA, BEN)))
                    .contains(StartRule.NO_RUNNER);
        }

        @Test
        @DisplayName("one Runner and somebody else is a hunt")
        void enough() {
            teams.joinRunners(ANNA);

            assertThat(mode.refuseStart(withGoal(true), Set.of(ANNA, BEN))).isEmpty();
        }

        @Test
        @DisplayName("the lobby's own death policy is never used — a death eliminates instead")
        void noDeathPolicy() {
            assertThat(mode.usesDeathPolicy()).isFalse();
        }
    }

    @Nested
    @DisplayName("starting a hunt")
    class Starting {

        @Test
        @DisplayName("whoever did not choose to run is put on the Hunter side")
        void everybodyElseHunts() {
            teams.joinRunners(ANNA);

            mode.onStart(runWith(Set.of(ANNA, BEN, CARO)));

            Hunt hunt = mode.current().orElseThrow();
            assertThat(hunt.runners()).containsExactly(ANNA);
            assertThat(hunt.hunters()).containsExactlyInAnyOrder(BEN, CARO);
            assertThat(teams.hunters()).as("and the team is what colours them for the match")
                    .containsExactlyInAnyOrder(BEN, CARO);
        }

        @Test
        @DisplayName("the Hunters are handed their compasses, and the hunt's own listeners are armed")
        void armsTheHunt() {
            teams.joinRunners(ANNA);

            mode.onStart(runWith(Set.of(ANNA, BEN)));

            verify(tracker).armFor(any(Hunt.class));
            ArgumentCaptor<Listener> listeners = ArgumentCaptor.forClass(Listener.class);
            verify(pluginManager, atLeastOnce()).registerEvents(listeners.capture(), eq(plugin));
            assertThat(listeners.getAllValues())
                    .anyMatch(listener -> listener instanceof HuntDeathListener);
        }

        @Test
        @DisplayName("only a Runner still in the hunt can win it at the goal")
        void goalIsTheRunners() {
            teams.joinRunners(ANNA);
            mode.onStart(runWith(Set.of(ANNA, BEN)));

            assertThat(mode.countsForGoal(ANNA)).isTrue();
            assertThat(mode.countsForGoal(BEN)).as("a Hunter killing the dragon wins nothing").isFalse();

            mode.current().orElseThrow().eliminate(ANNA);
            assertThat(mode.countsForGoal(ANNA)).as("and neither does a Runner already caught").isFalse();
        }

        @Test
        @DisplayName("with no hunt under way, nobody's goal counts")
        void noHuntNoGoal() {
            assertThat(mode.countsForGoal(ANNA)).isFalse();
            assertThat(mode.isRunning()).isFalse();
        }

        @Test
        @DisplayName("the door is shut on start and opened again at the end, when the owner asked for it")
        void closesTheDoor() throws Exception {
            settings.set(ManhuntSettings.DEFAULTS.withCloseWhitelistOnStart(true));
            when(whitelist.isClosed()).thenReturn(false);
            teams.joinRunners(ANNA);
            SpeedrunRun run = runWith(Set.of(ANNA, BEN));

            mode.onStart(run);
            verify(whitelist).close();

            run.session().finish("advancement:minecraft:end/kill_dragon");
            verify(whitelist).open();
        }

        @Test
        @DisplayName("a server that was already whitelisted is not thrown open by a hunt ending")
        void neverOpensSomebodyElsesDoor() throws Exception {
            settings.set(ManhuntSettings.DEFAULTS.withCloseWhitelistOnStart(true));
            when(whitelist.isClosed()).thenReturn(true);
            teams.joinRunners(ANNA);
            SpeedrunRun run = runWith(Set.of(ANNA, BEN));

            mode.onStart(run);
            run.session().finish("advancement:minecraft:end/kill_dragon");

            verify(whitelist, never()).close();
            verify(whitelist, never()).open();
        }
    }

    @Nested
    @DisplayName("ending one")
    class Ending {

        @Test
        @DisplayName("the compasses go back the moment it ends, not when somebody eventually leaves")
        void handsBackAtTheFinish() {
            teams.joinRunners(ANNA);
            SpeedrunRun run = runWith(Set.of(ANNA, BEN));
            mode.onStart(run);

            run.session().finish(HuntDeathListener.HUNTERS_WIN);

            verify(tracker).disarm(any(Hunt.class));
            assertThat(mode.current()).isEmpty();
        }

        @Test
        @DisplayName("forgetting the run afterwards hands nothing back twice")
        void bothPathsAreSafe() {
            teams.joinRunners(ANNA);
            SpeedrunRun run = runWith(Set.of(ANNA, BEN));
            mode.onStart(run);

            run.session().finish(HuntDeathListener.HUNTERS_WIN);
            run.disarm();

            verify(tracker, org.mockito.Mockito.times(1)).disarm(any(Hunt.class));
        }

        @Test
        @DisplayName("a run abandoned without ever finishing still puts everybody back")
        void abandonedRunIsCleanedUp() {
            teams.joinRunners(ANNA);
            SpeedrunRun run = runWith(Set.of(ANNA, BEN));
            mode.onStart(run);

            run.disarm();

            verify(tracker).disarm(any(Hunt.class));
            assertThat(mode.current()).isEmpty();
        }

        @Test
        @DisplayName("a hunt that outlives its plugin is ended by the module unloading")
        void forgetEndsIt() {
            teams.joinRunners(ANNA);
            mode.onStart(runWith(Set.of(ANNA, BEN)));

            mode.forget();

            assertThat(mode.isRunning()).isFalse();
            verify(tracker).disarm(any(Hunt.class));
        }
    }

    @Nested
    @DisplayName("saying who won")
    class Announcing {

        private Messages messages;
        private Player player;

        @BeforeEach
        void aPlayerToTell() {
            messages = mock(Messages.class);
            player = mock(Player.class);
            when(plugin.getServer().getPlayer(ANNA)).thenReturn(player);
            mode = new ManhuntMode(plugin, teams, mock(Eliminations.class), tracker,
                    new PortalMemory(), whitelist, messages, settings::get, null);
        }

        private void announce(String reason) {
            SpeedrunSession session = new SpeedrunSession(Set.of(ANNA));
            assertThat(mode.announceFinish(session,
                    new SpeedrunOutcome(reason, Duration.ofSeconds(125), Instant.now()))).isTrue();
        }

        @Test
        @DisplayName("the Runners reaching the goal is the Runners' win, in their own line")
        void runnersWin() {
            announce("advancement:minecraft:end/kill_dragon");

            verify(messages).send(eq(player), eq("manhunt.finished.runners"), eq("time"), eq("2:05"));
        }

        @Test
        @DisplayName("the last Runner caught is the Hunters' win")
        void huntersWin() {
            announce(HuntDeathListener.HUNTERS_WIN);

            verify(messages).send(eq(player), eq("manhunt.finished.hunters"), eq("time"), eq("2:05"));
        }

        @Test
        @DisplayName("an admin ending it is not a win for anybody, and is not announced as one")
        void stoppedIsNobodysWin() {
            announce("admin-reset");

            verify(messages).send(eq(player), eq("manhunt.finished.stopped"), eq("time"), eq("2:05"));
        }
    }

    /**
     * The one door a side changes through mid-hunt — the roster, the team and the compass moving
     * together. See {@link ManhuntMode#changeSide}.
     */
    @Nested
    @DisplayName("changing sides mid-hunt")
    class ChangingSides {

        private Player online(UUID id) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(id);
            when(plugin.getServer().getPlayer(id)).thenReturn(player);
            return player;
        }

        private void huntWith(UUID... runners) {
            teams.joinRunners(runners[0]);
            for (int i = 1; i < runners.length; i++) {
                teams.joinRunners(runners[i]);
            }
            mode.onStart(runWith(Set.of(ANNA, BEN, CARO)));
        }

        private void allowSwitching(boolean allowed) {
            settings.set(ManhuntSettings.DEFAULTS.withSideSwitchingMidHunt(allowed));
        }

        @Test
        @DisplayName("with no hunt on, it says so rather than touching the lobby's teams")
        void noHunt() {
            assertThat(mode.changeSide(ANNA, ManhuntMode.Side.HUNTER, false))
                    .isEqualTo(ManhuntMode.SideChange.NO_HUNT);
        }

        @Test
        @DisplayName("a server that fixes its sides refuses a player outright")
        void frozenForPlayers() {
            allowSwitching(false);
            huntWith(ANNA, BEN);

            assertThat(mode.changeSide(ANNA, ManhuntMode.Side.HUNTER, false))
                    .isEqualTo(ManhuntMode.SideChange.FROZEN);
            assertThat(mode.current().orElseThrow().isRunner(ANNA)).isTrue();
        }

        @Test
        @DisplayName("an admin's assign goes through on that same server")
        void forcedGoesThroughWhileFrozen() {
            allowSwitching(false);
            huntWith(ANNA, BEN);
            Player anna = online(ANNA);

            assertThat(mode.changeSide(ANNA, ManhuntMode.Side.HUNTER, true))
                    .isEqualTo(ManhuntMode.SideChange.CHANGED);

            assertThat(mode.current().orElseThrow().isHunter(ANNA)).isTrue();
            assertThat(teams.isHunter(ANNA)).as("the frozen team moved with the roster").isTrue();
            verify(tracker).give(anna);
        }

        @Test
        @DisplayName("a Runner turning Hunter is handed a compass and wears the Hunter team")
        void runnerToHunter() {
            allowSwitching(true);
            huntWith(ANNA, BEN);
            Player anna = online(ANNA);

            assertThat(mode.changeSide(ANNA, ManhuntMode.Side.HUNTER, false))
                    .isEqualTo(ManhuntMode.SideChange.CHANGED);

            verify(tracker).give(anna);
            verify(tracker, never()).takeFrom(anna);
            assertThat(teams.isHunter(ANNA)).isTrue();
        }

        @Test
        @DisplayName("a Hunter turning Runner has their compass taken away")
        void hunterToRunner() {
            allowSwitching(true);
            huntWith(ANNA);
            Player caro = online(CARO);

            assertThat(mode.changeSide(CARO, ManhuntMode.Side.RUNNER, false))
                    .isEqualTo(ManhuntMode.SideChange.CHANGED);

            verify(tracker).takeFrom(caro);
            assertThat(teams.isRunner(CARO)).isTrue();
            assertThat(mode.current().orElseThrow().isRunner(CARO)).isTrue();
        }

        @Test
        @DisplayName("the last Runner is refused, so a hunt is never ended by somebody leaving the side")
        void theLastRunnerIsRefused() {
            allowSwitching(true);
            huntWith(ANNA);
            online(ANNA);

            assertThat(mode.changeSide(ANNA, ManhuntMode.Side.HUNTER, true))
                    .isEqualTo(ManhuntMode.SideChange.LAST_RUNNER);
            assertThat(mode.current().orElseThrow().isRunner(ANNA)).isTrue();
        }

        @Test
        @DisplayName("somebody who is not in this hunt is refused")
        void notInTheHunt() {
            allowSwitching(true);
            huntWith(ANNA, BEN);
            UUID stranger = UUID.nameUUIDFromBytes("dan".getBytes());

            assertThat(mode.changeSide(stranger, ManhuntMode.Side.HUNTER, true))
                    .isEqualTo(ManhuntMode.SideChange.NOT_IN_THE_HUNT);
        }

        @Test
        @DisplayName("asking for the side they are already on changes nothing")
        void alreadyThere() {
            allowSwitching(true);
            huntWith(ANNA, BEN);

            assertThat(mode.changeSide(CARO, ManhuntMode.Side.HUNTER, false))
                    .isEqualTo(ManhuntMode.SideChange.ALREADY);
        }
    }
}
