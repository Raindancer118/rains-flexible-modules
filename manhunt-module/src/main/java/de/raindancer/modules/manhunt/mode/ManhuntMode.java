package de.raindancer.modules.manhunt.mode;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.service.Eliminations;
import de.raindancer.modules.manhunt.service.HuntDeathListener;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import de.raindancer.modules.manhunt.tracker.PortalMemory;
import de.raindancer.modules.manhunt.tracker.TrackerCompassService;
import de.raindancer.modules.manhunt.tracker.TrackerListener;
import de.raindancer.modules.speedrun.SpeedrunMode;
import de.raindancer.modules.speedrun.SpeedrunOutcome;
import de.raindancer.modules.speedrun.SpeedrunRun;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunSettings;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final TrackerCompassService tracker;
    private final PortalMemory portals;
    private final ManhuntWhitelistService whitelist;
    private final Messages messages;
    private final Supplier<ManhuntSettings> settings;
    private final Setup setup;

    /** The hunt in progress, or null between hunts. Read from the compass' timer and from events. */
    private final AtomicReference<Hunt> live = new AtomicReference<>();
    /** Whether this hunt was the one that shut the door, so only it ever opens it again. */
    private final AtomicBoolean closedTheWhitelist = new AtomicBoolean();

    public ManhuntMode(Plugin plugin, ManhuntTeams teams, Eliminations eliminations,
                       TrackerCompassService tracker, PortalMemory portals,
                       ManhuntWhitelistService whitelist, Messages messages,
                       Supplier<ManhuntSettings> settings, Setup setup) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.eliminations = Objects.requireNonNull(eliminations, "eliminations");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.portals = Objects.requireNonNull(portals, "portals");
        this.whitelist = Objects.requireNonNull(whitelist, "whitelist");
        this.messages = messages;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.setup = setup;
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
        return StartRule.refuse(config.hasAdvancementGoal(), participants, teams.runners());
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

    /** One of the two sides, as a command or a screen names it. */
    public enum Side { RUNNER, HUNTER }

    /** What {@link #changeSide} answered, so the caller can say why nothing happened. */
    public enum SideChange {
        /** Done: the hunt's roster, the team, and the compass are all in step again. */
        CHANGED,
        /** No hunt is running — the caller should do the ordinary lobby join instead. */
        NO_HUNT,
        /** Sides do not change mid-hunt on this server, and this was not an admin's call. */
        FROZEN,
        /** They are not in this hunt: a spectator, or somebody who joined after it began. */
        NOT_IN_THE_HUNT,
        /** They are already on that side. */
        ALREADY,
        /** Refused: they are the last Runner, and a hunt with nobody running is over by accident. */
        LAST_RUNNER
    }

    /**
     * Moves somebody between the two sides <em>while a hunt is being played</em>, keeping the three
     * things that have to agree in step: the hunt's own roster, the Core team they wear, and whether
     * they are carrying a tracking compass.
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
        Hunt.SideChange moved = side == Side.HUNTER
                ? hunt.moveToHunters(player)
                : hunt.moveToRunners(player);
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
            default -> { }
        }
        teams.evenWhileFrozen(() -> side == Side.HUNTER
                ? teams.joinHunters(player)
                : teams.joinRunners(player));
        Player online = plugin.getServer().getPlayer(player);
        if (online == null) {
            // Offline: the roster and the team are what matter, and the compass is handed out on
            // their next respawn or by the sweep when they come back. Nothing to carry yet.
            return SideChange.CHANGED;
        }
        if (side == Side.HUNTER) {
            // A Runner who was already caught is standing in spectator; they are a Hunter now, and a
            // Hunter who cannot touch anything is not hunting.
            eliminations.restore(online);
            tracker.give(online);
        } else {
            tracker.takeFrom(online);
        }
        if (messages != null) {
            messages.send(online, side == Side.HUNTER ? "manhunt.join.hunter" : "manhunt.join.runner");
        }
        return SideChange.CHANGED;
    }

    // ------------------------------------------------------------------------ a hunt beginning

    @Override
    public void onStart(SpeedrunRun run) {
        Hunt hunt = Hunt.of(run.participants(), teams.runners());
        live.set(hunt);
        // The sides as the hunt actually is, not as the lobby left them: everybody racing who did not
        // choose to run is chasing (see Hunt), and the team is what gives them the colour above their
        // head for the next twenty minutes. Written before anything reads the teams again.
        for (UUID hunter : hunt.hunters()) {
            teams.joinHunters(hunter);
        }
        portals.clear();
        tracker.armFor(hunt);
        run.listen(new TrackerListener(hunt, tracker, portals));
        run.listen(new HuntDeathListener(plugin, hunt, run.session(), eliminations, messages));

        if (settings.get().closeWhitelistOnStart() && !whitelist.isClosed()) {
            // Only when it was open: a server whose owner runs it whitelisted all the time must not
            // have its door thrown open by a hunt ending.
            whitelist.close();
            closedTheWhitelist.set(true);
        }

        // Both paths, because they answer different failures. onFinish is the hunt ending properly —
        // the compasses go and the spectators stand up the moment it is over, not when somebody
        // eventually leaves the world. onDisarm is the lobby forgetting the run at all, which also
        // covers a run abandoned without ever finishing; it is written to be safe to run twice.
        run.session().onFinish(outcome -> endTheHunt(hunt));
        run.onDisarm(() -> endTheHunt(hunt));
    }

    /** Everything a hunt borrowed, given back. Safe to call more than once — see {@link #onStart}. */
    private void endTheHunt(Hunt hunt) {
        if (!live.compareAndSet(hunt, null)) {
            return;   // already ended, by whichever of the two paths got here first
        }
        tracker.disarm(hunt);
        eliminations.restoreAll(hunt);
        portals.clear();
        if (closedTheWhitelist.compareAndSet(true, false)) {
            whitelist.open();
        }
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

    static String formatted(java.time.Duration elapsed) {
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
