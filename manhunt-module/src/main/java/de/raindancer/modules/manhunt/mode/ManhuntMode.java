package de.raindancer.modules.manhunt.mode;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.geometry.Ring;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.service.AbsentRunners;
import de.raindancer.modules.manhunt.service.Eliminations;
import de.raindancer.modules.manhunt.service.HuntDeathListener;
import de.raindancer.modules.manhunt.service.HuntWatcher;
import de.raindancer.modules.manhunt.service.HunterHoldListener;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.manhunt.tracker.HuntCompasses;
import de.raindancer.modules.manhunt.tracker.PortalMemory;
import de.raindancer.modules.manhunt.tracker.TrackerListener;
import de.raindancer.modules.manhunt.util.Threads;
import de.raindancer.modules.speedrun.SpeedrunMode;
import de.raindancer.modules.speedrun.SpeedrunOutcome;
import de.raindancer.modules.speedrun.SpeedrunRun;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunSettings;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Manhunt, as a game played in the speedrun lobby.
 *
 * <h2>What this class is, and what it deliberately is not</h2>
 * It is the whole of the hunt's lifecycle: what may start one, who is on which side once it does,
 * what the Hunters carry, what a death costs, and how it ends. It is <em>not</em> a lobby, a world, a
 * countdown, a clock, a start point or a reset — every one of those is the speedrun lobby's, already
 * written and already tested, and the module this replaces had a second copy of each. See
 * {@link SpeedrunMode} for what that cost in front of players.
 *
 * <h2>The Runners' win is the lobby's own goal</h2>
 * Whatever {@code speedrun.yml} says a race is won by — the dragon and the exit portal, or any other
 * advancement — is what the Runners are running for, judged by the lobby's own end condition, with
 * {@link #countsForGoal} narrowing it to Runners still in the hunt. A Hunter who kills the dragon has
 * won nothing, and a Runner already caught cannot win it from spectator.
 */
public final class ManhuntMode implements SpeedrunMode {

    private static final LogChannel log = Log.of("manhunt");

    /** The id in {@code speedrun.yml}'s {@code game-mode}, and the key this is withdrawn by. */
    public static final String ID = "manhunt";

    private final Plugin plugin;
    private final ManhuntTeams teams;
    private final Eliminations eliminations;
    private final HuntCompasses compasses;
    private final PortalMemory portals;
    private final ManhuntWhitelistService whitelist;
    private final Messages messages;
    private final Supplier<ManhuntSettings> settings;
    private final Setup setup;

    /** The hunt in progress, or null between hunts. Read from the compass' timer and from events. */
    private final AtomicReference<Hunt> live = new AtomicReference<>();
    private final AtomicReference<SpeedrunSession> liveSession = new AtomicReference<>();

    /** Nobody is left on the Runner side: ended, won by nobody. */
    public static final String RUNNERS_LEFT = "manhunt:runners-left";
    /** Nobody is left chasing: ended, won by nobody. */
    public static final String HUNTERS_LEFT = "manhunt:hunters-left";
    /** Told everything that happens to a hunt — the record and the HUD. */
    private volatile HuntWatcher watcher = HuntWatcher.NONE;

    /** Runs a task this many ticks later — the global scheduler, or a test's own list. */
    private BiConsumer<Long, Runnable> later;

    /** The gap between neighbours on the starting circle, and the smallest circle there is. */
    static final double CIRCLE_SPACING = 4;
    static final double CIRCLE_MIN_RADIUS = 5;

    public ManhuntMode(Plugin plugin, ManhuntTeams teams, Eliminations eliminations,
                       HuntCompasses compasses, PortalMemory portals,
                       ManhuntWhitelistService whitelist, Messages messages,
                       Supplier<ManhuntSettings> settings, Setup setup) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.eliminations = Objects.requireNonNull(eliminations, "eliminations");
        this.compasses = Objects.requireNonNull(compasses, "compasses");
        this.portals = Objects.requireNonNull(portals, "portals");
        this.whitelist = Objects.requireNonNull(whitelist, "whitelist");
        this.messages = messages;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.setup = setup;
        this.later = (ticks, task) -> Scheduling.globalLater(plugin, ticks, task);
    }

    /** Who is told everything that happens to a hunt — see {@link HuntWatcher}. */
    public void watch(HuntWatcher watcher) {
        this.watcher = Objects.requireNonNull(watcher, "watcher");
    }

    /** For tests: how a delayed task is run instead of the server's scheduler. */
    void laterWith(BiConsumer<Long, Runnable> runner) {
        this.later = Objects.requireNonNull(runner, "runner");
    }

    // ------------------------------------------------------------------------ what the lobby asks

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Manhunt";
    }

    @Override
    public Material icon() {
        return Material.TARGET;
    }

    @Override
    public List<String> description() {
        return List.of("<gray>Runners race the goal; everybody else hunts them.",
                "<gray>A caught Runner is out for good.");
    }

    @Override
    public Optional<String> refuseStart(SpeedrunSettings config, Set<UUID> participants) {
        return StartRule.refuse(participants, teams.runners());
    }

    @Override
    public boolean endsItself() {
        return true;   // the last Runner caught — a hunt without a goal is the Runners surviving
    }

    /** A death eliminates a Runner and leaves the Hunters playing — never the lobby's own policy. */
    @Override
    public boolean usesDeathPolicy() {
        return false;
    }

    @Override
    public boolean countsForGoal(UUID participant) {
        Hunt hunt = live.get();
        return hunt != null && hunt.isRunner(participant) && !hunt.isEliminated(participant);
    }

    @Override
    public Optional<Setup> setup() {
        return Optional.ofNullable(setup);
    }

    /** The hunt in progress — what the compass, the commands and the sides screen all read. */
    public Optional<Hunt> current() {
        return Optional.ofNullable(live.get());
    }

    /** Whether a hunt is under way, which is also what freezes the two sides. */
    public boolean isRunning() {
        return live.get() != null;
    }

    // ------------------------------------------------------------------------ changing sides

    public enum LeaveOutcome { LEFT, NOT_IN_THE_HUNT, NO_HUNT }

    /**
     * {@code /manhunt leave} mid-hunt: off the roster, the team and the run's own participants, a
     * caught Runner out of spectator. The caller takes the compasses. A side left empty ends the hunt, won by nobody —
     * unless every Runner still in it is already caught, which is the Hunters' win it always was.
     */
    public LeaveOutcome leaveHunt(UUID player) {
        Hunt hunt = live.get();
        if (hunt == null) {
            return LeaveOutcome.NO_HUNT;
        }
        if (!hunt.remove(player)) {
            return LeaveOutcome.NOT_IN_THE_HUNT;
        }
        teams.evenWhileFrozen(() -> teams.leave(player));
        compasses.forget(player);
        watcher.left(hunt, player);
        Player online = plugin.getServer().getPlayer(player);
        if (online != null) {
            eliminations.restoreOnTheirThread(online);
        }
        SpeedrunSession session = liveSession.get();
        if (session != null && live.get() == hunt) {
            // Off the run's roster too, so the clock and the goal stop counting them. Refused only for
            // the run's very last participant — and a hunt that small has just lost a whole side,
            // which ends it right below.
            session.removeParticipant(player);
            if (hunt.runners().isEmpty()) {
                session.finish(RUNNERS_LEFT);
            } else if (hunt.allRunnersOut()) {
                session.finish(HuntDeathListener.HUNTERS_WIN);
            } else if (hunt.hunters().isEmpty()) {
                session.finish(HUNTERS_LEFT);
            }
        }
        return LeaveOutcome.LEFT;
    }

    /** One of the two sides, as a command or a screen names it. */
    public enum Side { RUNNER, HUNTER }

    /** What {@link #changeSide} answered, and the line that says so ({@code <player>}, {@code <side>}). */
    public enum SideChange {
        /** Done: the hunt's roster, the team, and the compasses are all in step again. */
        CHANGED("manhunt.side.changed"),
        /** No hunt is running — the caller should do the ordinary lobby join instead. */
        NO_HUNT("manhunt.side.hunt-over"),
        /** Sides do not change mid-hunt on this server, and this was not an admin's call. */
        FROZEN("manhunt.sides-frozen"),
        /** Not in this hunt, and not an admin bringing them in — a latecomer may not add themselves. */
        NOT_IN_THE_HUNT("manhunt.side.not-in-hunt"),
        /** They are already on that side. */
        ALREADY("manhunt.side.already"),
        /** Refused: they are the last Runner, and a hunt with nobody running is over by accident. */
        LAST_RUNNER("manhunt.side.last-runner"),
        /** Refused: they are the last Hunter, and a hunt with nobody chasing is over by accident. */
        LAST_HUNTER("manhunt.side.last-hunter");

        private final String messageKey;

        SideChange(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    /**
     * Moves somebody between the two sides <em>while a hunt is being played</em>, keeping the three
     * things that have to agree in step: the hunt's own roster, the Core team they wear, and the
     * compasses they carry.
     *
     * <h2>Why all three move together, in one method</h2>
     * Because every bug in the module this replaced was two of them disagreeing. A Hunter without a
     * compass cannot hunt; a Runner carrying one is being pointed at themselves; a team that says
     * Hunter over somebody the hunt still counts as a Runner ends the round with the wrong winner.
     * There is exactly one door, and {@code ManhuntTeams.evenWhileFrozen} is what makes it the only
     * one — an ordinary {@code /manhunt join} mid-hunt is still refused.
     *
     * @param force an admin's {@code /manhunt assign}, which is allowed through even on a server
     *              where players may not switch for themselves
     */
    public SideChange changeSide(UUID player, Side side, boolean force) {
        Hunt hunt = live.get();
        if (hunt == null) {
            return SideChange.NO_HUNT;
        }
        if (!force && !settings.get().sideSwitchingMidHunt()) {
            return SideChange.FROZEN;
        }
        Hunt.SideChange moved;
        boolean latecomer = force && !hunt.everybody().contains(player);
        if (latecomer) {
            // A latecomer, or somebody who left: an admin may bring them in. Into the run too, so the
            // clock, the goal and the finish line count them.
            hunt.join(player, side == Side.RUNNER);
            SpeedrunSession session = liveSession.get();
            if (session != null) {
                session.addParticipant(player);
            }
            whitelist.admit(player);
            moved = Hunt.SideChange.MOVED;
        } else {
            moved = side == Side.HUNTER
                    ? hunt.moveToHunters(player)
                    : hunt.moveToRunners(player);
        }
        switch (moved) {
            case ALREADY_THERE -> {
                return SideChange.ALREADY;
            }
            case NOT_IN_THE_HUNT -> {
                return SideChange.NOT_IN_THE_HUNT;
            }
            case LAST_RUNNER -> {
                return SideChange.LAST_RUNNER;
            }
            case LAST_HUNTER -> {
                return SideChange.LAST_HUNTER;
            }
            default -> { }
        }
        teams.evenWhileFrozen(() -> side == Side.HUNTER
                ? teams.joinHunters(player)
                : teams.joinRunners(player));
        watcher.sideChanged(hunt, player, side == Side.RUNNER, latecomer);
        Player online = plugin.getServer().getPlayer(player);
        if (online == null) {
            // Offline: the roster and the team are what matter. Their compasses are fitted when
            // they log back in — see HuntCompasses.onJoin.
            return SideChange.CHANGED;
        }
        if (latecomer) {
            // Somebody watching the hunt in spectator is playing it now.
            Threads.entity(plugin, online, () -> {
                if (online.getGameMode() == GameMode.SPECTATOR) {
                    online.setGameMode(GameMode.SURVIVAL);
                }
            });
        }
        if (side == Side.HUNTER) {
            // A Runner who was already caught is standing in spectator; they are a Hunter now, and a
            // Hunter who cannot touch anything is not hunting.
            eliminations.restoreOnTheirThread(online);
        }
        compasses.refit(hunt, online);
        if (messages != null) {
            messages.send(online, side == Side.HUNTER ? "manhunt.join.hunter" : "manhunt.join.runner");
        }
        return SideChange.CHANGED;
    }

    // ------------------------------------------------------------------------ where everybody stands

    /**
     * With {@link ManhuntSettings#startInCircle()}, everybody evenly around one circle for the
     * countdown, facing the middle — asked for as "make everyone spawn in a circle". The geometry is
     * Core's ({@code Ring}); this only chooses the order and puts each spot on the ground.
     */
    @Override
    public Map<UUID, Location> startingSpots(Location centre, Set<UUID> participants) {
        if (!settings.get().startInCircle() || centre == null || centre.getWorld() == null) {
            return Map.of();
        }
        World world = centre.getWorld();
        List<UUID> order = circleOrder(participants);
        List<Ring.Spot> spots = Ring.around(centre.getX(), centre.getZ(), order.size(),
                CIRCLE_SPACING, CIRCLE_MIN_RADIUS);
        Map<UUID, Location> placed = new LinkedHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            Ring.Spot spot = spots.get(i);
            int blockX = (int) Math.floor(spot.x());
            int blockZ = (int) Math.floor(spot.z());
            // The middle of the block, one above whatever is highest there: on the ground, never in it.
            placed.put(order.get(i), new Location(world, blockX + 0.5,
                    world.getHighestBlockYAt(blockX, blockZ) + 1, blockZ + 0.5, spot.yaw(), 0f));
        }
        return placed;
    }

    /** Runners first and Hunters after, each side standing together; stable within a side. */
    List<UUID> circleOrder(Set<UUID> participants) {
        Set<UUID> runners = teams.runners();
        Comparator<UUID> byId = Comparator.comparing(UUID::toString);
        List<UUID> order = new ArrayList<>(participants.stream()
                .filter(runners::contains).sorted(byId).toList());
        order.addAll(participants.stream().filter(id -> !runners.contains(id)).sorted(byId).toList());
        return order;
    }

    // ------------------------------------------------------------------------ a hunt beginning

    @Override
    public void onStart(SpeedrunRun run) {
        Hunt hunt = Hunt.of(run.participants(), teams.runners());
        live.set(hunt);
        // The sides as the hunt actually is, not as the lobby left them: everybody racing who did not
        // choose to run is chasing (see Hunt), and the team is what gives them the colour above their
        // head for the next twenty minutes. Written before anything reads the teams again — through
        // the freeze, which the line above has just closed.
        teams.evenWhileFrozen(() -> {
            hunt.hunters().forEach(teams::joinHunters);
            return null;
        });
        portals.clear();
        compasses.armFor(hunt);
        run.listen(new TrackerListener(hunt, compasses.tracker(), portals));
        HuntWatcher watching = watcher;
        // One hold for the run, head start or not: it is also where a Hunter waits after dying.
        HunterHoldListener hold = new HunterHoldListener(hunt);
        run.listen(hold);
        run.session().onFinish(outcome -> hold.release());
        run.onDisarm(hold::release);
        run.listen(new HuntDeathListener(plugin, hunt, run.session(), eliminations, messages,
                () -> settings.get().runnerLivesClamped(), hold,
                () -> settings.get().hunterRespawnDelayClamped(), watching));
        AbsentRunners absent = new AbsentRunners(plugin, hunt, run.session(), () -> live.get() == hunt,
                () -> settings.get().runnerOfflineGraceSecondsClamped(),
                (ticks, task) -> later.accept(ticks, task), messages, watching);
        run.listen(absent);
        // Somebody who logged out during the countdown is a participant who never quits again.
        for (UUID runner : hunt.runners()) {
            if (plugin.getServer().getPlayer(runner) == null) {
                absent.away(runner);
            }
        }
        int headStart = run.resumed() ? 0 : settings.get().headStartFor(hunt.runners().size(), hunt.hunters().size());
        holdTheHunters(hunt, hold, headStart);

        if (settings.get().closeWhitelistOnStart()) {
            whitelist.closeForHunt();
        }

        // Both paths, because they answer different failures. onFinish is the hunt ending properly —
        // the compasses go and the spectators stand up the moment it is over, not when somebody
        // eventually leaves the world. onDisarm is the lobby forgetting the run at all, which also
        // covers a run abandoned without ever finishing; it is written to be safe to run twice.
        liveSession.set(run.session());
        run.session().onFinish(outcome -> endTheHunt(hunt));
        run.onDisarm(() -> endTheHunt(hunt));
        watching.started(hunt, run, hold, headStart);
    }

    /**
     * The Runners' head start: the Hunters stand still and touch nothing for
     * {@link ManhuntSettings#headStartFor} seconds. The hold goes with the run through
     * {@code run.listen}, so a hunt ending early never leaves anybody frozen.
     */
    private void holdTheHunters(Hunt hunt, HunterHoldListener hold, int seconds) {
        if (seconds <= 0) {
            hold.release();
            return;
        }
        tell(hunt, "manhunt.head-start.begun", "seconds", String.valueOf(seconds));
        later.accept(seconds * 20L, () -> {
            hold.release();
            if (live.get() == hunt) {
                tell(hunt, "manhunt.head-start.over");
            }
        });
    }

    private void tell(Hunt hunt, String key, String... placeholders) {
        if (messages == null) {
            return;
        }
        for (UUID id : hunt.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                messages.send(player, key, (Object[]) placeholders);
            }
        }
    }

    /** Everything a hunt borrowed, given back. Safe to call more than once — see {@link #onStart}. */
    private void endTheHunt(Hunt hunt) {
        if (!live.compareAndSet(hunt, null)) {
            return;   // already ended, by whichever of the two paths got here first
        }
        SpeedrunSession ended = liveSession.getAndSet(null);
        watcher.ended(hunt, ended == null ? Optional.empty() : ended.outcome());
        compasses.disarm(hunt);
        eliminations.restoreAll(hunt);
        portals.clear();
        whitelist.reopenAfterHunt();
    }

    // ------------------------------------------------------------------------ how it ended

    /**
     * Says who won, in the hunt's own words rather than the lobby's "run finished".
     *
     * <p>Read off the outcome's reason, which is the only record of what actually ended it:
     * {@link HuntDeathListener#HUNTERS_WIN} is the Hunters catching the last Runner, an
     * {@code advancement:…} is a Runner reaching the goal, and anything else — an admin's reset, the
     * plugin unloading — is a hunt that ended without being won, which is not a win to announce.
     */
    @Override
    public boolean announceFinish(SpeedrunSession session, SpeedrunOutcome outcome) {
        if (messages == null) {
            return false;
        }
        String reason = outcome.reason() == null ? "" : outcome.reason();
        String key;
        if (HuntDeathListener.HUNTERS_WIN.equals(reason)) {
            key = "manhunt.finished.hunters";
        } else if (reason.startsWith("advancement:")) {
            key = "manhunt.finished.runners";
        } else {
            key = "manhunt.finished.stopped";
        }
        String time = formatted(outcome.elapsed());
        for (UUID id : session.participants()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                messages.send(player, key, "time", time);
            }
        }
        return true;
    }

    static String formatted(Duration elapsed) {
        long seconds = elapsed == null ? 0 : elapsed.getSeconds();
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    /** For the module's own disable: a hunt that outlives its plugin is nobody's. */
    public void forget() {
        Hunt hunt = live.get();
        if (hunt != null) {
            log.info("The plugin is unloading while a hunt is under way; putting everybody back.");
            endTheHunt(hunt);
        }
    }
}
