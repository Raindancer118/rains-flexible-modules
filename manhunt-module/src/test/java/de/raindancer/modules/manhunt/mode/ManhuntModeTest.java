package de.raindancer.modules.manhunt.mode;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.service.Eliminations;
import de.raindancer.modules.manhunt.service.HuntDeathListener;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.manhunt.tracker.HuntCompasses;
import de.raindancer.modules.manhunt.tracker.PortalMemory;
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
    private HuntCompasses compasses;
    private Eliminations eliminations;
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

        // Frozen exactly as the module wires it: for as long as a hunt is running. A test that never
        // froze hid that the start itself wrote to the teams through the freeze and was refused.
        teams = new ManhuntTeams(() -> mode != null && mode.isRunning());
        whitelist = mock(ManhuntWhitelistService.class);
        PortalMemory portals = new PortalMemory();
        compasses = mock(HuntCompasses.class);
        when(compasses.tracker()).thenReturn(mock(TrackerCompassService.class));
        settings = new AtomicReference<>(ManhuntSettings.DEFAULTS);
        eliminations = mock(Eliminations.class);
        mode = new ManhuntMode(plugin, teams, eliminations, compasses, portals, whitelist,
                mock(Messages.class), settings::get, null);
        // Every timer the mode sets — the head start, a Runner's grace — swallowed unless a test asks.
        mode.laterWith((ticks, task) -> { });
    }

    private SpeedrunRun lastRun;

    /** A run the lobby would have handed the mode, built without a lobby. */
    private SpeedrunRun runWith(Set<UUID> participants) {
        lastRun = new SpeedrunRun(plugin, new SpeedrunSession(participants),
                SpeedrunWorlds.around("speedrun"));
        return lastRun;
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

        @Test
        @DisplayName("the last Runner leaving their side during the countdown stops the start at zero")
        void lastRunnerLeavesDuringTheCountdown() {
            teams.joinRunners(ANNA);
            teams.leave(ANNA);   // /manhunt leave before the hunt exists — the lobby asks again at zero

            assertThat(mode.refuseStart(withGoal(true), Set.of(ANNA, BEN))).contains(StartRule.NO_RUNNER);
        }

        @Test
        @DisplayName("an assign during the countdown is the side the hunt starts with")
        void assignDuringTheCountdown() {
            teams.joinRunners(BEN);   // what /manhunt assign does while no hunt is live

            mode.onStart(runWith(Set.of(ANNA, BEN)));

            assertThat(mode.current().orElseThrow().runners()).containsExactly(BEN);
        }

        @Test
        @DisplayName("no goal is needed: catching the last Runner ends a hunt by itself")
        void noGoalNeeded() {
            teams.joinRunners(ANNA);

            assertThat(mode.endsItself()).isTrue();
            assertThat(mode.refuseStart(withGoal(false), Set.of(ANNA, BEN))).isEmpty();
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

            verify(compasses).armFor(any(Hunt.class));
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
            teams.joinRunners(ANNA);
            SpeedrunRun run = runWith(Set.of(ANNA, BEN));

            mode.onStart(run);
            verify(whitelist).closeForHunt();

            run.session().finish("advancement:minecraft:end/kill_dragon");
            verify(whitelist).reopenAfterHunt();
        }

        @Test
        @DisplayName("a resumed hunt holds nobody — the head start was given in the hunt it continues")
        void resumedHuntHasNoHeadStart() {
            settings.set(new ManhuntSettings(
                    de.raindancer.modules.manhunt.ManhuntSettings.CrossWorldTracking.LAST_PORTAL, true, true, 10,
                    false, de.raindancer.modules.manhunt.ManhuntSettings.TeamCompassItem.RECOVERY_COMPASS, true,
                    false, true, false, true, false, false, 30, false, 300,
                    1, 0, 0, 10, 0, true, true, true, true, true, 20, 0));
            java.util.List<Long> waits = new java.util.ArrayList<>();
            mode.laterWith((ticks, task) -> waits.add(ticks));
            when(plugin.getServer().getPlayer(ANNA)).thenReturn(mock(Player.class));
            teams.joinRunners(ANNA);

            mode.onStart(new SpeedrunRun(plugin, new SpeedrunSession(Set.of(ANNA, BEN)),
                    SpeedrunWorlds.around("speedrun"), true));
            assertThat(waits).isEmpty();

            mode.forget();
            mode.onStart(runWith(Set.of(ANNA, BEN)));
            assertThat(waits).containsExactly(600L);
        }

        @Test
        @DisplayName("a Runner who logged out during the countdown starts their grace the moment the hunt does")
        void offlineAtTheStart() {
            java.util.List<Runnable> timers = new java.util.ArrayList<>();
            mode.laterWith((ticks, task) -> timers.add(task));
            teams.joinRunners(ANNA);
            teams.joinRunners(BEN);
            when(plugin.getServer().getPlayer(BEN)).thenReturn(mock(Player.class));
            when(plugin.getServer().getOfflinePlayer(ANNA)).thenReturn(mock(org.bukkit.OfflinePlayer.class));
            mode.onStart(runWith(Set.of(ANNA, BEN, CARO)));

            timers.forEach(Runnable::run);

            assertThat(mode.current().orElseThrow().isEliminated(ANNA)).isTrue();
            assertThat(mode.current().orElseThrow().isEliminated(BEN)).as("online all along").isFalse();
        }

        @Test
        @DisplayName("with the setting off, a hunt never shuts the door")
        void settingOffLeavesTheDoor() {
            teams.joinRunners(ANNA);

            mode.onStart(runWith(Set.of(ANNA, BEN)));

            verify(whitelist, never()).closeForHunt();
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

            verify(compasses).disarm(any(Hunt.class));
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

            verify(compasses, org.mockito.Mockito.times(1)).disarm(any(Hunt.class));
        }

        @Test
        @DisplayName("the goal and the last catch in the same tick: the first one stands, cleaned up once")
        void twoEndingsAtOnce() {
            teams.joinRunners(ANNA);
            SpeedrunRun run = runWith(Set.of(ANNA, BEN));
            mode.onStart(run);

            run.session().finish("advancement:minecraft:end/kill_dragon");
            run.session().finish(HuntDeathListener.HUNTERS_WIN);

            assertThat(run.session().outcome().orElseThrow().reason()).startsWith("advancement:");
            verify(compasses, org.mockito.Mockito.times(1)).disarm(any(Hunt.class));
        }

        @Test
        @DisplayName("the plugin unloading and the lobby shutting down both clean up — once")
        void disableAndLobbyShutdown() {
            teams.joinRunners(ANNA);
            SpeedrunRun run = runWith(Set.of(ANNA, BEN));
            mode.onStart(run);

            mode.forget();
            run.disarm();

            verify(compasses, org.mockito.Mockito.times(1)).disarm(any(Hunt.class));
            verify(whitelist, org.mockito.Mockito.times(1)).reopenAfterHunt();
        }

        @Test
        @DisplayName("a run abandoned without ever finishing still puts everybody back")
        void abandonedRunIsCleanedUp() {
            teams.joinRunners(ANNA);
            SpeedrunRun run = runWith(Set.of(ANNA, BEN));
            mode.onStart(run);

            run.disarm();

            verify(compasses).disarm(any(Hunt.class));
            assertThat(mode.current()).isEmpty();
        }

        @Test
        @DisplayName("a hunt that outlives its plugin is ended by the module unloading")
        void forgetEndsIt() {
            teams.joinRunners(ANNA);
            mode.onStart(runWith(Set.of(ANNA, BEN)));

            mode.forget();

            assertThat(mode.isRunning()).isFalse();
            verify(compasses).disarm(any(Hunt.class));
        }
    }

    /** {@code /manhunt leave} while a hunt is being played — see {@link ManhuntMode#leaveHunt}. */
    @Nested
    @DisplayName("leaving mid-hunt")
    class Leaving {

        private final UUID dan = UUID.nameUUIDFromBytes("dan".getBytes());

        private void huntWith(Set<UUID> runners, Set<UUID> everybody) {
            runners.forEach(teams::joinRunners);
            mode.onStart(runWith(everybody));
        }

        @Test
        @DisplayName("off the roster, off the team, and off the run itself — the clock no longer counts them")
        void outOfEverything() {
            huntWith(Set.of(ANNA, BEN), Set.of(ANNA, BEN, CARO, dan));

            assertThat(mode.leaveHunt(ANNA)).isEqualTo(ManhuntMode.LeaveOutcome.LEFT);

            assertThat(mode.current().orElseThrow().everybody()).doesNotContain(ANNA);
            assertThat(teams.everybody()).doesNotContain(ANNA);
            assertThat(lastRun.session().participants()).doesNotContain(ANNA);
            assertThat(lastRun.session().outcome()).as("one Runner and two Hunters play on").isEmpty();
            verify(compasses).forget(ANNA);
        }

        @Test
        @DisplayName("a caught Runner who leaves is stood up again")
        void caughtRunnerStandsUp() {
            huntWith(Set.of(ANNA, BEN), Set.of(ANNA, BEN, CARO));
            mode.current().orElseThrow().eliminate(ANNA);
            Player anna = mock(Player.class);
            when(plugin.getServer().getPlayer(ANNA)).thenReturn(anna);

            mode.leaveHunt(ANNA);

            verify(eliminations).restoreOnTheirThread(anna);
        }

        @Test
        @DisplayName("the last Runner leaving ends it, won by nobody")
        void lastRunner() {
            huntWith(Set.of(ANNA), Set.of(ANNA, BEN));

            mode.leaveHunt(ANNA);

            assertThat(lastRun.session().outcome().orElseThrow().reason()).isEqualTo(ManhuntMode.RUNNERS_LEFT);
            assertThat(mode.isRunning()).isFalse();
        }

        @Test
        @DisplayName("the last Runner still running leaving, with the rest caught, is the Hunters' win")
        void lastLivingRunner() {
            huntWith(Set.of(ANNA, BEN), Set.of(ANNA, BEN, CARO));
            mode.current().orElseThrow().eliminate(BEN);

            mode.leaveHunt(ANNA);

            assertThat(lastRun.session().outcome().orElseThrow().reason())
                    .isEqualTo(HuntDeathListener.HUNTERS_WIN);
        }

        @Test
        @DisplayName("the last Hunter leaving ends it, won by nobody")
        void lastHunter() {
            huntWith(Set.of(ANNA), Set.of(ANNA, BEN));

            mode.leaveHunt(BEN);

            assertThat(lastRun.session().outcome().orElseThrow().reason()).isEqualTo(ManhuntMode.HUNTERS_LEFT);
        }

        @Test
        @DisplayName("the run's very last participant leaving still ends it, though the run keeps them on its roster")
        void lastParticipant() {
            huntWith(Set.of(ANNA), Set.of(ANNA, BEN));
            mode.leaveHunt(BEN);   // ends it: nobody chasing

            assertThat(mode.leaveHunt(ANNA)).isEqualTo(ManhuntMode.LeaveOutcome.NO_HUNT);
        }

        @Test
        @DisplayName("somebody not in it, or no hunt at all, is said so and nothing moves")
        void notInIt() {
            assertThat(mode.leaveHunt(ANNA)).isEqualTo(ManhuntMode.LeaveOutcome.NO_HUNT);
            huntWith(Set.of(ANNA), Set.of(ANNA, BEN));

            assertThat(mode.leaveHunt(dan)).isEqualTo(ManhuntMode.LeaveOutcome.NOT_IN_THE_HUNT);
            assertThat(lastRun.session().participants()).containsExactlyInAnyOrder(ANNA, BEN);
        }

        @Test
        @DisplayName("once left, a player cannot put themselves back — only an admin's assign can")
        void noWayBackButAssign() {
            settings.set(ManhuntSettings.DEFAULTS.withSideSwitchingMidHunt(true));
            huntWith(Set.of(ANNA, BEN), Set.of(ANNA, BEN, CARO));
            mode.leaveHunt(BEN);

            assertThat(mode.changeSide(BEN, ManhuntMode.Side.RUNNER, false))
                    .isEqualTo(ManhuntMode.SideChange.NOT_IN_THE_HUNT);
            assertThat(mode.changeSide(BEN, ManhuntMode.Side.RUNNER, true))
                    .isEqualTo(ManhuntMode.SideChange.CHANGED);
            assertThat(lastRun.session().participants()).contains(BEN);
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
            mode = new ManhuntMode(plugin, teams, mock(Eliminations.class), compasses,
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
            verify(compasses).refit(mode.current().orElseThrow(), anna);
        }

        @Test
        @DisplayName("a Runner turning Hunter is handed a compass and wears the Hunter team")
        void runnerToHunter() {
            allowSwitching(true);
            huntWith(ANNA, BEN);
            Player anna = online(ANNA);

            assertThat(mode.changeSide(ANNA, ManhuntMode.Side.HUNTER, false))
                    .isEqualTo(ManhuntMode.SideChange.CHANGED);

            // Every compass, not only the tracking one: the structure compass is a Runner's and the
            // team compass points at a side — both used to stay as they were.
            verify(compasses).refit(mode.current().orElseThrow(), anna);
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

            verify(compasses).refit(mode.current().orElseThrow(), caro);
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
        @DisplayName("the last Hunter is refused too — a hunt with nobody chasing is over by accident")
        void theLastHunterIsRefused() {
            allowSwitching(true);
            teams.joinRunners(ANNA);
            teams.joinRunners(BEN);
            mode.onStart(runWith(Set.of(ANNA, BEN, CARO)));
            online(CARO);

            assertThat(mode.changeSide(CARO, ManhuntMode.Side.RUNNER, false))
                    .isEqualTo(ManhuntMode.SideChange.LAST_HUNTER);
            assertThat(mode.current().orElseThrow().isHunter(CARO)).isTrue();
            verify(compasses, never()).refit(any(), any());
        }

        @Test
        @DisplayName("somebody who is not in this hunt may not put themselves in it")
        void notInTheHunt() {
            allowSwitching(true);
            huntWith(ANNA, BEN);
            UUID stranger = UUID.nameUUIDFromBytes("dan".getBytes());

            assertThat(mode.changeSide(stranger, ManhuntMode.Side.HUNTER, false))
                    .isEqualTo(ManhuntMode.SideChange.NOT_IN_THE_HUNT);
            assertThat(mode.current().orElseThrow().everybody()).doesNotContain(stranger);
        }

        @Test
        @DisplayName("an admin's assign brings a latecomer into the running hunt, compass and clock included")
        void latecomerAssigned() {
            huntWith(ANNA, BEN);
            UUID dan = UUID.nameUUIDFromBytes("dan".getBytes());
            Player player = online(dan);
            when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SPECTATOR);

            assertThat(mode.changeSide(dan, ManhuntMode.Side.HUNTER, true))
                    .isEqualTo(ManhuntMode.SideChange.CHANGED);

            assertThat(mode.current().orElseThrow().isHunter(dan)).isTrue();
            assertThat(teams.hunters()).contains(dan);
            assertThat(lastRun.session().participants()).contains(dan);
            verify(compasses).refit(mode.current().orElseThrow(), player);
            // Somebody who was not online when a hunt shut the door could not get back in after a
            // disconnect — the grace exists for exactly that.
            verify(whitelist).admit(dan);
            verify(player).setGameMode(org.bukkit.GameMode.SURVIVAL);
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

    @Nested
    @DisplayName("the Runners' head start")
    class HeadStart {

        private final java.util.List<Long> delays = new java.util.ArrayList<>();
        private final java.util.List<Runnable> releases = new java.util.ArrayList<>();

        @BeforeEach
        void noRealScheduler() {
            // Online, so the only timer is the head start's — an offline Runner starts their grace.
            when(plugin.getServer().getPlayer(ANNA)).thenReturn(mock(Player.class));
            mode.laterWith((ticks, task) -> {
                delays.add(ticks);
                releases.add(task);
            });
        }

        private de.raindancer.modules.manhunt.service.HunterHoldListener registeredHold() {
            ArgumentCaptor<Listener> listeners = ArgumentCaptor.forClass(Listener.class);
            verify(pluginManager, atLeastOnce()).registerEvents(listeners.capture(), eq(plugin));
            return listeners.getAllValues().stream()
                    .filter(l -> l instanceof de.raindancer.modules.manhunt.service.HunterHoldListener)
                    .map(l -> (de.raindancer.modules.manhunt.service.HunterHoldListener) l)
                    .findFirst().orElse(null);
        }

        @Test
        @DisplayName("with a head start, the Hunters are held and let go after exactly that long")
        void heldThenReleased() {
            settings.set(ManhuntSettings.DEFAULTS.withHunterHeadStartSeconds(30));
            teams.joinRunners(ANNA);

            mode.onStart(runWith(Set.of(ANNA, BEN)));

            var hold = registeredHold();
            assertThat(hold).isNotNull();
            assertThat(hold.isHolding()).isTrue();
            assertThat(delays).containsExactly(600L);

            releases.getFirst().run();
            assertThat(hold.isHolding()).isFalse();
        }

        @Test
        @DisplayName("a hunt that ends during the head start lets the Hunters go at once")
        void endedDuringTheHeadStart() {
            settings.set(ManhuntSettings.DEFAULTS.withHunterHeadStartSeconds(30));
            teams.joinRunners(ANNA);
            mode.onStart(runWith(Set.of(ANNA, BEN)));
            var hold = registeredHold();

            lastRun.session().finish(HuntDeathListener.HUNTERS_WIN);

            assertThat(hold.isHolding()).as("not frozen until a timer nobody needs any more").isFalse();
        }

        @Test
        @DisplayName("with none, nobody is held and nothing is scheduled")
        void noHeadStart() {
            teams.joinRunners(ANNA);

            mode.onStart(runWith(Set.of(ANNA, BEN)));

            // Registered all the same: it is also the respawn wait.
            assertThat(registeredHold().isHolding()).isFalse();
            assertThat(delays).isEmpty();
        }

        @Test
        @DisplayName("a bigger pack gives the Runners a longer head start")
        void headStartScalesWithThePack() {
            settings.set(ManhuntSettings.DEFAULTS.withHunterHeadStartSeconds(30).withHeadStartPerHunterSeconds(10));
            teams.joinRunners(ANNA);

            mode.onStart(runWith(Set.of(ANNA, BEN, CARO, UUID.randomUUID())));

            assertThat(delays).containsExactly((30L + 2 * 10) * 20);
        }

        @Test
        @DisplayName("whoever watches the hunt is told it began, with the hold and the head start")
        void watcherStarted() {
            settings.set(ManhuntSettings.DEFAULTS.withHunterHeadStartSeconds(30));
            int[] told = {-1};
            mode.watch(new de.raindancer.modules.manhunt.service.HuntWatcher() {
                @Override
                public void started(Hunt hunt, SpeedrunRun run,
                                    de.raindancer.modules.manhunt.service.HunterHoldListener hold, int headStart) {
                    told[0] = headStart;
                }
            });
            teams.joinRunners(ANNA);

            mode.onStart(runWith(Set.of(ANNA, BEN)));

            assertThat(told[0]).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("starting in a circle")
    class Circle {

        private org.bukkit.Location centre() {
            org.bukkit.World world = mock(org.bukkit.World.class);
            when(world.getHighestBlockYAt(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt())).thenReturn(70);
            return new org.bukkit.Location(world, 100.5, 64, -20.5);
        }

        @Test
        @DisplayName("off, the lobby places everybody as usual")
        void off() {
            assertThat(mode.startingSpots(centre(), Set.of(ANNA, BEN))).isEmpty();
        }

        @Test
        @DisplayName("on, everybody gets a spot on one circle, on the ground, facing the middle")
        void on() {
            settings.set(ManhuntSettings.DEFAULTS.withStartInCircle(true));
            teams.joinRunners(ANNA);

            var spots = mode.startingSpots(centre(), Set.of(ANNA, BEN, CARO));

            assertThat(spots).containsOnlyKeys(ANNA, BEN, CARO);
            double radius = Math.hypot(spots.get(ANNA).getX() - 100.5, spots.get(ANNA).getZ() + 20.5);
            for (org.bukkit.Location spot : spots.values()) {
                assertThat(spot.getY()).isEqualTo(71);
                assertThat(Math.hypot(spot.getX() - 100.5, spot.getZ() + 20.5))
                        .isCloseTo(radius, org.assertj.core.api.Assertions.within(1.0));
            }
        }

        @Test
        @DisplayName("the Runners stand next to each other, not scattered among the Hunters")
        void runnersTogether() {
            settings.set(ManhuntSettings.DEFAULTS.withStartInCircle(true));
            teams.joinRunners(ANNA);
            teams.joinRunners(CARO);

            java.util.List<UUID> order = mode.circleOrder(Set.of(ANNA, BEN, CARO));

            assertThat(order.subList(0, 2)).containsExactlyInAnyOrder(ANNA, CARO);
            assertThat(order.get(2)).isEqualTo(BEN);
        }
    }
}
