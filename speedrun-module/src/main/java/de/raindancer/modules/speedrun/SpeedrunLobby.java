package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.moderation.players.PlayerAdmin;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.RainsCore;
import de.raindancer.core.world.manage.WorldRegenerator;
import de.raindancer.core.world.manage.WorldSeed;
import de.raindancer.modules.speedrun.conditions.AdvancementEndCondition;
import de.raindancer.modules.speedrun.conditions.DeathEndCondition;
import de.raindancer.modules.speedrun.conditions.DragonExitEndCondition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one speedrun world: its configuration, its current {@link SpeedrunSession} if it has one, and
 * the auto-reset that happens once a finished run's last participant has left.
 *
 * <h2>Why one world, not a set of them</h2>
 * The speedrun map is a single world with no bookkeeping of its own — {@link WorldRegenerator} (Core's
 * generic create/delete/regenerate) has no notion of a set either, unlike {@code FarmWorlds}' own
 * {@code WorldSet} machinery. This class is the same shape one level up: a lobby has a configuration
 * and, at most, one live session, never a roster of named worlds.
 *
 * <h2>Why the world state is not stored anywhere</h2>
 * It is derived: no session means {@link SpeedrunLobbyState#READY}, and otherwise it mirrors the
 * session's own {@link SpeedrunState}. A separate flag would be a second fact that could disagree
 * with the session that is sitting right here — the same reasoning {@code SpeedrunSession} uses for
 * why {@code outcome()} is read from the one field {@link SpeedrunSession#finish} writes.
 *
 * <h2>What does not survive a restart</h2>
 * A run in progress. {@link SpeedrunSession} is pure in-memory, by design (see its class javadoc),
 * and nothing here changes that — a server restarted mid-run comes back {@link SpeedrunLobbyState#READY}
 * with the map exactly as the run left it, not mid-run. Only the configuration — the world name, the
 * advancement goal, the death policy — is persisted, through {@link #settings}.
 */
public final class SpeedrunLobby {

    private static final LogChannel log = Log.of("speedrun");

    /** What {@link #start} answered, so a caller can tell a player why nothing happened. */
    public enum StartOutcome {
        /** A session now exists and is running. */
        STARTED,
        /** A run is already under way (running, paused, or finished and not yet reset). */
        NOT_READY,
        /** Neither an advancement goal nor a death policy is configured — a run that could never end. */
        NO_END_CONDITION,
        /** Nobody was handed in to run it. */
        NO_PARTICIPANTS,
        /** The configured lobby world is not currently loaded. */
        WORLD_MISSING,
        /** {@code game-mode} names a mode whose module is not installed — see {@link SpeedrunModes}. */
        MODE_MISSING,
        /** The chosen game mode refused this start; {@link #refusalFor} says why, in words. */
        REFUSED_BY_MODE,
        /** The chosen game mode threw while starting, and nothing was left running. */
        MODE_FAILED
    }

    /**
     * How the lobby waits — the seam between "in ten seconds, remake the world" and Paper's own
     * scheduler, so the wait after a finished run can be driven by a test rather than slept through.
     */
    @FunctionalInterface
    public interface DelayedTask {
        void in(long ticks, Runnable task);
    }

    /** What {@link #forceReset} answered. */
    public enum ResetOutcome {
        /** Whatever run there was is ended, and the world is being deleted and remade. */
        RESET,
        /** A countdown is in flight; see {@link #forceReset}'s own note on why this refuses rather
         *  than racing it. */
        COUNTDOWN_IN_PROGRESS
    }

    private final Plugin plugin;
    private final SettingsStore<SpeedrunSettings> settings;
    private final WorldRegenerator worldRegenerator = new WorldRegenerator();
    /** Not final: the public constructor below sets this itself, after delegating to the private one,
     *  because the lambda it builds reads {@link #released} — an instance field it may not reach from
     *  inside a {@code this(...)} call's own argument list. */
    private SpeedrunCountdownLauncher countdownLauncher;
    private final Messages messages;
    /** {@code null} for a lobby built without an {@link ActionBars} — the run clock is simply not shown. */
    private final SpeedrunTimerDisplay timerDisplay;
    /** {@code null} for a lobby built without a {@link PlayerAdmin} — nothing is reset before a run starts. */
    private final SpeedrunPreparation preparation;

    private SpeedrunSession session;
    /** The live run as the chosen game mode sees it — {@code null} for a plain race and between runs. */
    private SpeedrunRun run;
    /** Registered fresh for every session, so a finished run's listener does not linger. */
    private SpeedrunOccupancyListener occupancy;
    /** Registered alongside {@link #occupancy}, for the same reason and on the same lifecycle. */
    private SpeedrunCreeperOnBreakListener creeperOnBreak;
    /** Registered alongside {@link #occupancy}, for the same reason and on the same lifecycle. */
    private SpeedrunCreeperOnContainerOpenListener creeperOnContainerOpen;
    /** Set the moment {@link #beginCountdown} launches one, cleared the moment it completes. */
    private boolean countingDown;
    /** Who {@code /lemmemove} has exempted from the READY/COUNTDOWN movement freeze — see {@link #release}.
     *  Thread-safe because the command that grants this may run on a different region thread under Folia
     *  than the move event checking it. */
    private final Set<UUID> released = ConcurrentHashMap.newKeySet();
    /** Told once the world has actually come back from a reset — see {@link #onReady}. */
    private final List<Runnable> readyListeners = new CopyOnWriteArrayList<>();
    /** Who was standing in the run's worlds when it reset itself, and is owed a way back into the
     *  fresh lobby — see {@link #resetForAnotherRun}. Cleared as they are sent. */
    private final Set<UUID> owedAWayBack = ConcurrentHashMap.newKeySet();
    /** How a wait is scheduled. Core's global scheduler in production; a test drives it by hand. */
    private DelayedTask later;
    /** Who {@code /speedrunspectate} has marked as not racing — excluded from a start block's sweep of
     *  "everybody in the lobby world" until they toggle it off again. Same thread-safety reasoning as
     *  {@link #released}. */
    private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();

    /** No countdown, no finish announcement, no action-bar clock, no start-of-run reset —
     *  {@link #beginCountdown} is not usable from this alone. */
    public SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings) {
        this(plugin, settings, null, null, null, null);
    }

    public SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings, BossBars bossBars,
                         Effects effects, Messages messages, ActionBars actionBars, PlayerAdmin players) {
        this(plugin, settings, null, messages,
                actionBars == null ? null
                        : new SpeedrunTimerDisplay(actionBars, SpeedrunTimerDisplay.viaScheduling(plugin)),
                players == null ? null : new SpeedrunPreparation(players));
        this.countdownLauncher = (participants, onComplete) ->
                new SpeedrunCountdown(plugin, bossBars, effects, participants, onComplete, released).begin();
        if (timerDisplay != null) {
            // Set here rather than handed to the constructor for the same reason as the launcher
            // above: this lambda reads the lobby's own configuration, and a this(...) call's argument
            // list may not reach the instance being built.
            timerDisplay.alsoShowTo(this::onlookers);
        }
    }

    /** For tests: a fake {@link SpeedrunCountdownLauncher} that never touches a live server. */
    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings,
                 SpeedrunCountdownLauncher countdownLauncher) {
        this(plugin, settings, countdownLauncher, null, null, null);
    }

    /** For tests: exercises the finish announcement without a live server. */
    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings,
                 SpeedrunCountdownLauncher countdownLauncher, Messages messages) {
        this(plugin, settings, countdownLauncher, messages, null, null);
    }

    /** For tests: also exercises the action-bar clock without a live server or scheduler. */
    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings,
                 SpeedrunCountdownLauncher countdownLauncher, SpeedrunTimerDisplay timerDisplay) {
        this(plugin, settings, countdownLauncher, null, timerDisplay, null);
    }

    /** For tests: also exercises the start-of-run reset without a live server. */
    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings,
                 SpeedrunCountdownLauncher countdownLauncher, SpeedrunPreparation preparation) {
        this(plugin, settings, countdownLauncher, null, null, preparation);
    }

    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings,
                 SpeedrunCountdownLauncher countdownLauncher, Messages messages,
                 SpeedrunTimerDisplay timerDisplay, SpeedrunPreparation preparation) {
        this.plugin = plugin;
        this.settings = settings;
        this.countdownLauncher = countdownLauncher;
        this.preparation = preparation;
        this.messages = messages;
        this.timerDisplay = timerDisplay;
        this.later = (ticks, task) -> Scheduling.globalLater(plugin, ticks, task);
    }

    /** For tests: runs the waits by hand instead of through Paper's scheduler. */
    void schedulesLaterWith(DelayedTask later) {
        this.later = later;
    }

    public SpeedrunSettings config() {
        return settings.current();
    }

    /** For the GUI: writing a setting goes through the store, so a click and a hand-edited
     *  {@code speedrun.yml} can never disagree — same reasoning as {@code FarmWorldConfigMenu}. */
    public SettingsStore<SpeedrunSettings> settings() {
        return settings;
    }

    public Optional<SpeedrunSession> session() {
        return Optional.ofNullable(session);
    }

    /**
     * The game mode this lobby is set to, if one is chosen <em>and</em> installed. Empty for a plain
     * race — and also for a mode named in {@code game-mode} whose module is not on the server, which
     * is why {@link #validate} answers {@link StartOutcome#MODE_MISSING} rather than quietly racing
     * plain: somebody who set the lobby to Manhunt and pressed the block meant to play Manhunt.
     */
    public Optional<SpeedrunMode> mode() {
        return SpeedrunModes.find(config().gameMode());
    }

    /**
     * Why the chosen mode would refuse a start with {@code participants}, as a wording key — empty
     * when it would not, and for a plain race. Asked by whoever sends the refusal, so the reason a
     * player reads is the mode's own rather than a generic "not right now".
     */
    public Optional<String> refusalFor(Collection<UUID> participants) {
        return mode().flatMap(mode -> mode.refuseStart(config(), Set.copyOf(participants)));
    }

    /** Where the lobby is right now — see the class javadoc for why this is derived, not stored. */
    public SpeedrunLobbyState state() {
        if (countingDown) {
            return SpeedrunLobbyState.COUNTDOWN;
        }
        if (session == null) {
            return SpeedrunLobbyState.READY;
        }
        return switch (session.state()) {
            case NOT_STARTED, RUNNING -> SpeedrunLobbyState.RUNNING;
            case PAUSED -> SpeedrunLobbyState.PAUSED;
            case FINISHED -> SpeedrunLobbyState.FINISHED;
        };
    }

    /**
     * Exempts {@code player} from the movement freeze — the READY-state one in
     * {@code SpeedrunLobbyListener.onMove} and {@link SpeedrunCountdown}'s own — for {@code /lemmemove}.
     *
     * <p>They stay a participant; this only lifts the freeze itself. That is a deliberate escape hatch
     * for somebody stuck (wedged in terrain, desynced by a bug) rather than a way to skip the wait
     * while still racing fairly — an admin reaching for this on somebody who is not actually stuck is
     * choosing to give them a head start.
     */
    public void release(UUID player) {
        if (player != null) {
            released.add(player);
        }
    }

    /**
     * Takes a {@link #release} back, for {@code /freezeagain} — the freeze applies to them again from
     * the next step they take. A release never expires on its own, so without this the only way out of
     * one handed to the wrong name was to reset the world around everybody.
     *
     * @return whether they were actually released, so the command can say "there was nothing to undo"
     *         rather than confirm something that did not happen
     */
    public boolean refreeze(UUID player) {
        return player != null && released.remove(player);
    }

    /** Whether {@code player} has been exempted from the movement freeze by {@link #release}. */
    public boolean isReleased(UUID player) {
        return player != null && released.contains(player);
    }

    /**
     * Flips whether {@code player} counts as a "not racing" spectator — excluded from the roster a
     * start-block press sweeps up, until they toggle this off again. Sticky on purpose: this is for
     * somebody who does not race at all (staff, an observer), not a per-run choice to remake every time.
     *
     * @return the new state — {@code true} means they are now a spectator
     */
    public boolean toggleSpectator(UUID player) {
        if (player == null) {
            return false;
        }
        if (!spectators.add(player)) {
            spectators.remove(player);
            return false;
        }
        return true;
    }

    /** Whether {@code player} has opted out of being swept into a race — see {@link #toggleSpectator}. */
    public boolean isSpectator(UUID player) {
        return player != null && spectators.contains(player);
    }

    /**
     * Freezes {@code participants} for a few seconds and then, if nothing has changed underneath it,
     * starts the run — see {@link SpeedrunCountdown}. The lobby reports {@link SpeedrunLobbyState#COUNTDOWN}
     * for the whole window, which is what refuses a second press of the start block mid-countdown.
     *
     * <p>Validated twice: once here, before the countdown is even shown, so a hopeless press (no end
     * condition configured, say) is refused instantly rather than after a five-second wait; and again
     * inside {@link #start} when the countdown actually completes, in case the configuration or the
     * roster changed in between.
     *
     * <p>Teleports everybody in {@code participants} to {@code /starthere}'s configured point first,
     * if one is set ({@link SpeedrunSettings#startPointSet()}) — "as soon as the countdown begins" is
     * before the freeze they are about to sit through, not after it, so nobody spends the countdown
     * standing wherever they happened to press the block from.
     */
    public StartOutcome beginCountdown(Collection<UUID> participants) {
        StartOutcome problem = validate(participants);
        if (problem != null) {
            return problem;
        }
        if (countdownLauncher == null) {
            log.error("beginCountdown() was called on a SpeedrunLobby built without a countdown "
                    + "launcher — that constructor is for tests only.");
            return StartOutcome.NOT_READY;
        }
        Set<UUID> frozen = Set.copyOf(participants);
        teleportToStartPoint(frozen);
        countingDown = true;
        countdownLauncher.begin(frozen, () -> {
            countingDown = false;
            start(frozen);
        });
        return StartOutcome.STARTED;
    }

    private void teleportToStartPoint(Set<UUID> participants) {
        // Empty when /starthere never ran — nobody is moved then, which is the documented "off".
        Location point = startPoint().orElse(null);
        if (point == null) {
            return;
        }
        for (UUID id : participants) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.teleportAsync(point);
            }
        }
    }

    /**
     * Where {@code /starthere} put the start line, empty when it never ran or the lobby world is not
     * loaded — see {@link #setStartPoint}.
     */
    public Optional<Location> startPoint() {
        SpeedrunSettings current = config();
        if (!current.startPointSet()) {
            return Optional.empty();
        }
        return world().map(target -> new Location(target, current.startX(), current.startY(),
                current.startZ(), (float) current.startYaw(), (float) current.startPitch()));
    }

    /**
     * Where somebody who left the run's worlds without meaning to belongs: the start line if one was
     * set, the lobby world's spawn otherwise, and empty when that world is not loaded at all.
     *
     * @see SpeedrunRespawnListener
     */
    public Optional<Location> wayBackIn() {
        Optional<Location> point = startPoint();
        return point.isPresent() ? point : world().map(World::getSpawnLocation);
    }

    /**
     * Told once, every time a reset actually completes and the world is back — never told about a
     * reset that failed, since nothing usable came of it. Registered by whoever hands out the lobby
     * items, so anybody already standing in the fresh world (having joined or teleported in while the
     * old one was still finishing up) gets them the moment there is something to do with them, rather
     * than waiting for a join or a teleport that may never come again.
     */
    public void onReady(Runnable listener) {
        if (listener != null) {
            readyListeners.add(listener);
        }
    }

    private void announceReady() {
        sendBackWhoeverIsOwedAWayIn();
        for (Runnable listener : readyListeners) {
            try {
                listener.run();
            } catch (RuntimeException broken) {
                log.error(broken, "A speedrun onReady listener threw.");
            }
        }
    }

    /**
     * Records where {@code /starthere} was typed as the point every participant is teleported to when
     * a countdown begins — see {@link #beginCountdown}. Through the settings store, the same as every
     * other value the lobby menu writes, so a click and a hand-edited {@code speedrun.yml} can never
     * disagree.
     */
    public void setStartPoint(Location location) {
        settings.set("start-x", String.valueOf(location.getX()));
        settings.set("start-y", String.valueOf(location.getY()));
        settings.set("start-z", String.valueOf(location.getZ()));
        settings.set("start-yaw", String.valueOf(location.getYaw()));
        settings.set("start-pitch", String.valueOf(location.getPitch()));
        settings.set("start-point-set", "true");
    }

    /**
     * Forgets whatever {@code /starthere} set — called by every reset, from
     * {@link #regenerateTheWholeRun}.
     *
     * <h2>Why a reset throws it away rather than keeping it</h2>
     * The point is coordinates in a world that the reset deletes. The world that comes back is
     * generated from a new seed, so the spot those numbers name is somewhere else entirely — mid-air,
     * inside a mountain, in an ocean — and the next countdown would teleport every racer into it
     * without anyone having asked for that. Reported after exactly that: a start point set before a
     * reset was still the start point after one. Cleared outright rather than guessed at, since a
     * lobby with no start point simply starts everybody where they are standing, which is the
     * documented "off".
     */
    public void clearStartPoint() {
        settings.set("start-x", "0");
        settings.set("start-y", "0");
        settings.set("start-z", "0");
        settings.set("start-yaw", "0");
        settings.set("start-pitch", "0");
        settings.set("start-point-set", "false");
    }

    /**
     * An admin's own escape hatch: whatever the speedrun world currently is — mid-run, freshly
     * regenerated and untouched, half-built by somebody poking around in it while READY — this ends
     * any run under way and hands the world to {@link WorldRegenerator#regenerate}: everybody standing
     * in it is evacuated (back to wherever they were before they arrived, not a generic spawn — see
     * {@code WorldEntryPoints}), every one of its files is deleted, and a brand new world is made from
     * scratch. Not "revert to some earlier state": the old world is gone.
     *
     * <p>Refuses only during {@link SpeedrunLobbyState#COUNTDOWN}, rather than racing it:
     * {@link #beginCountdown} has already scheduled a callback that will call {@link #start} once it
     * fires, and nothing here can reach into {@link SpeedrunCountdownLauncher} to cancel that. Deleting
     * the world out from under a countdown that is about to start a session in it would not stop that
     * session from starting seconds later, in a world that may not have finished being created yet.
     */
    public ResetOutcome forceReset() {
        if (countingDown) {
            return ResetOutcome.COUNTDOWN_IN_PROGRESS;
        }
        World target = world().orElse(null);
        if (session != null && session.state() != SpeedrunState.FINISHED) {
            session.finish("admin-reset");
        }
        disarmSession();
        if (target == null) {
            log.warn("The speedrun world '{}' is not loaded; nothing to regenerate.", config().worldName());
            return ResetOutcome.RESET;
        }
        regenerateTheWholeRun(target);
        return ResetOutcome.RESET;
    }

    /**
     * Wipes all three worlds a run is played across — the lobby world and the {@code _nether} and
     * {@code _the_end} beside it — and tells {@link #onReady} listeners once they are back.
     *
     * <p>Resetting only the overworld would leave a finished run's nether and end standing: the
     * chests looted, the portal already lit, the dragon already dead. A companion that is not loaded
     * is simply not part of the group.
     *
     * <p><b>One operation, not three.</b> This used to regenerate the overworld and then each
     * companion in turn, and whoever was still standing in the nether was then sent back to where they
     * had entered it from — the overworld that had just been deleted. The nether's reset died on that,
     * and the end's was never reached. Core's {@link WorldRegenerator#regenerateAll} moves every
     * occupant of every world out of the whole group, waits for all of them, and only then unloads
     * anything, so there is nobody left to strand.
     *
     * <p>Folia: unloading, deleting and recreating a world are global-region operations, and callers
     * reach this from whatever thread a command or a quit event ran on.
     */
    private void regenerateTheWholeRun(World target) {
        // Before anything else: the point /starthere set is coordinates in the world about to be
        // deleted, and the one that comes back is a different world under the same name. See
        // clearStartPoint.
        clearStartPoint();
        SpeedrunWorlds worlds = SpeedrunWorlds.around(config().worldName());
        List<World> group = new ArrayList<>();
        group.add(target);
        for (String companion : List.of(worlds.nether(), worlds.theEnd())) {
            World loaded = Bukkit.getWorld(companion);
            if (loaded != null) {
                group.add(loaded);
            }
        }
        Scheduling.global(plugin, () -> regenerator().regenerateAll(group, WorldSeed.random(), ok -> {
            if (!ok) {
                log.warn("Not every world of the run could be regenerated ({}); the server log says "
                        + "which, and that one still holds whatever the last run left in it.",
                        String.join(", ", group.stream().map(World::getName).toList()));
            }
            // Announced whenever the overworld itself came back, even if a companion did not: a lobby
            // that never says it is ready again is one nobody can start a run in, which is worse than
            // a used nether. A different World object is the proof it is a new one — an overworld
            // that refused to unload is still the old object.
            World now = Bukkit.getWorld(config().worldName());
            if (now != null && now != target) {
                announceReady();
            }
        }));
    }

    /**
     * Core's regenerator when Core is running — the one that writes every seed into the seed history —
     * and a plain one otherwise, which is what a test without a server gets.
     */
    private WorldRegenerator regenerator() {
        return RainsCore.isAvailable() ? RainsCore.get().worldRegenerator() : worldRegenerator;
    }

    /**
     * Starts a run with {@code participants} immediately, with no countdown — {@link #beginCountdown}
     * is what a player's click actually reaches; this is what it calls once the countdown ends, and
     * what a test calls directly to exercise the actual session-building without waiting on one.
     *
     * <p>Arms every end condition the current configuration names — an {@link AdvancementEndCondition}
     * when {@link SpeedrunSettings#hasAdvancementGoal()} (a {@link DragonExitEndCondition} instead,
     * when that goal is the vanilla dragon kill and {@link SpeedrunSettings#requireExitPortalAfterDragon()}
     * is on), a {@link DeathEndCondition} when {@link SpeedrunSettings#hasDeathCondition()} — and
     * registers a fresh {@link SpeedrunOccupancyListener} so the clock pauses while every participant
     * is offline. Also runs {@link SpeedrunPreparation}, if this lobby was built with a
     * {@code PlayerAdmin} — full health, full hunger, no leftover effects or fire, and the world
     * itself set to morning with every hostile mob and dropped item cleared — so a run always begins
     * from the same standard conditions, whatever state the map was left in.
     */
    public StartOutcome start(Collection<UUID> participants) {
        StartOutcome problem = validate(participants);
        if (problem != null) {
            return problem;
        }
        SpeedrunSettings current = config();
        SpeedrunMode chosen = mode().orElse(null);
        SpeedrunSession fresh = new SpeedrunSession(Set.copyOf(participants));
        // Who reaching the goal actually ends the run. Every participant in a plain race; in Manhunt
        // only a Runner, since a Hunter killing the dragon has won the Runners nothing. Asked of the
        // mode at the moment it happens rather than snapshotted here, so a side changing mid-run —
        // which Manhunt does not allow, but another mode might — is answered as it stands then.
        java.util.function.Predicate<UUID> countsForGoal =
                chosen == null ? participant -> true : chosen::countsForGoal;
        if (current.hasAdvancementGoal()) {
            NamespacedKey key = NamespacedKey.fromString(current.advancementKey());
            if (key != null) {
                fresh.addEndCondition(current.isDragonKillGoal() && current.requireExitPortalAfterDragon()
                        ? new DragonExitEndCondition(plugin, key, countsForGoal)
                        : new AdvancementEndCondition(plugin, key, countsForGoal));
            } else {
                log.warn("'{}' is not a valid advancement key; the advancement goal was skipped.",
                        current.advancementKey());
            }
        }
        if (current.hasDeathCondition() && (chosen == null || chosen.usesDeathPolicy())) {
            DeathEndCondition.DeathPolicy policy = current.deathPolicy() == SpeedrunDeathPolicy.ALL
                    ? DeathEndCondition.DeathPolicy.ALL : DeathEndCondition.DeathPolicy.ANY;
            fresh.addEndCondition(new DeathEndCondition(plugin, policy));
        }

        session = fresh;
        occupancy = new SpeedrunOccupancyListener(fresh);
        plugin.getServer().getPluginManager().registerEvents(occupancy, plugin);
        creeperOnBreak = new SpeedrunCreeperOnBreakListener(fresh, settings);
        plugin.getServer().getPluginManager().registerEvents(creeperOnBreak, plugin);
        creeperOnContainerOpen = new SpeedrunCreeperOnContainerOpenListener(fresh, settings);
        plugin.getServer().getPluginManager().registerEvents(creeperOnContainerOpen, plugin);
        fresh.onFinish(outcome -> announceFinish(fresh, outcome));
        fresh.onFinish(outcome -> restartAfterFinish());
        if (preparation != null) {
            preparation.prepare(world().orElse(null), fresh.participants(), current.timeAtStart());
        }
        // Wherever each of them actually is the moment the run begins — the configured /starthere
        // point if one was set (teleportToStartPoint already moved them there before the countdown),
        // or simply where they happened to be standing if not — becomes where they respawn, so a death
        // that does not end the run (or one being fixed up manually) puts them back at their own start,
        // never the server's own spawn or an old bed miles from the race.
        //
        // Folia: a countdown reaching zero runs on whatever thread its timer owns, which is not the
        // one owning each racer — reading a player's location and writing their respawn point from
        // anywhere else throws. Each hop lands on the racer's own thread.
        for (UUID id : fresh.participants()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Scheduling.entity(plugin, player,
                        () -> player.setRespawnLocation(player.getLocation(), true));
            }
        }
        if (chosen != null) {
            SpeedrunRun theRun = new SpeedrunRun(plugin, fresh, SpeedrunWorlds.around(current.worldName()));
            run = theRun;
            try {
                chosen.onStart(theRun);
            } catch (Throwable broken) {
                // A mode that could not arm itself must not leave a plain race running in its place:
                // everybody pressed start expecting that game, the lobby items are already gone, and
                // a race nobody chose is a worse answer than none. So the run is forgotten outright
                // and the lobby goes back to ready, which hands the items out again.
                log.error(broken, "The game mode '{}' failed to start; the run was abandoned and the "
                        + "lobby is ready again.", chosen.id());
                disarmSession();
                announceReady();
                return StartOutcome.MODE_FAILED;
            }
        }
        if (timerDisplay != null) {
            timerDisplay.start(fresh);
        }
        fresh.start();
        return StartOutcome.STARTED;
    }

    /**
     * Tells every participant still online what ended the run and how long it took — the confirmation
     * that they raced under the settings they saw in the lobby menu, not a silent state change nobody
     * but the boss bar noticed.
     */
    private void announceFinish(SpeedrunSession finished, SpeedrunOutcome outcome) {
        SpeedrunMode chosen = mode().orElse(null);
        if (chosen != null) {
            try {
                if (chosen.announceFinish(finished, outcome)) {
                    return;   // the mode said it in its own words; one ending, announced once
                }
            } catch (RuntimeException broken) {
                log.error(broken, "The game mode '{}' threw while announcing the finish; the plain "
                        + "line is sent instead.", chosen.id());
            }
        }
        if (messages == null) {
            return;
        }
        String reason = friendlyReason(outcome.reason());
        String time = formatted(outcome.elapsed());
        for (UUID participant : finished.participants()) {
            Player player = Bukkit.getPlayer(participant);
            if (player != null) {
                messages.send(player, "speedrun.finished", "reason", reason, "time", time);
            }
        }
    }

    /** {@code "advancement:minecraft:end/kill_dragon"} → the advancement's own display name, and so on. */
    private static String friendlyReason(String reason) {
        if (reason == null) {
            return "?";
        }
        if (reason.startsWith("advancement:")) {
            return SpeedrunAdvancementChooser.friendlyName(reason.substring("advancement:".length()));
        }
        if (reason.equals("death-all")) {
            return "everybody died";
        }
        if (reason.startsWith("death:")) {
            try {
                OfflinePlayer who = Bukkit.getOfflinePlayer(UUID.fromString(reason.substring("death:".length())));
                String name = who.getName();
                return (name == null ? "somebody" : name) + " died";
            } catch (IllegalArgumentException notAUuid) {
                return "somebody died";
            }
        }
        return reason;
    }

    private static String formatted(java.time.Duration elapsed) {
        long seconds = elapsed.getSeconds();
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    /** @return the reason a run cannot start right now, or {@code null} when it can. */
    private StartOutcome validate(Collection<UUID> participants) {
        if (state() != SpeedrunLobbyState.READY) {
            return StartOutcome.NOT_READY;
        }
        SpeedrunSettings current = config();
        SpeedrunMode chosen = mode().orElse(null);
        if (current.hasGameMode() && chosen == null) {
            return StartOutcome.MODE_MISSING;
        }
        // A mode that keeps the death policy can be ended by it; one that does not — Manhunt, where
        // a death eliminates a Runner rather than ending the run — needs the goal to exist, or the
        // Runners would have nothing to win by.
        boolean endable = chosen == null || chosen.usesDeathPolicy()
                ? current.hasEndCondition()
                : current.hasAdvancementGoal();
        if (!endable) {
            return StartOutcome.NO_END_CONDITION;
        }
        if (participants == null || participants.isEmpty()) {
            return StartOutcome.NO_PARTICIPANTS;
        }
        if (world().isEmpty()) {
            return StartOutcome.WORLD_MISSING;
        }
        if (chosen != null && chosen.refuseStart(current, Set.copyOf(participants)).isPresent()) {
            return StartOutcome.REFUSED_BY_MODE;
        }
        return null;
    }

    /**
     * Called on every quit; a no-op unless a run has finished and its last participant just left, in
     * which case the world is regenerated and the lobby returns to {@link SpeedrunLobbyState#READY}.
     *
     * <p>By id and nothing else — same reasoning as {@link SpeedrunOccupancyListener#onQuit}: the
     * quitting player may still answer {@code Bukkit.getPlayer} for part of this event's handling, so
     * they are excluded explicitly rather than trusted to already be gone from
     * {@code Bukkit.getOnlinePlayers()}.
     *
     * @param quitting the player who just quit, so they can be excluded from "is anybody still here"
     */
    public void resetIfAbandoned(UUID quitting) {
        if (state() != SpeedrunLobbyState.FINISHED) {
            return;
        }
        for (UUID participant : session.participants()) {
            if (participant.equals(quitting)) {
                continue;
            }
            if (Bukkit.getPlayer(participant) != null) {
                return;
            }
        }
        World target = world().orElse(null);
        disarmSession();
        if (target == null) {
            log.warn("The finished run's world '{}' is not loaded; nothing to regenerate.",
                    config().worldName());
            return;
        }
        regenerateTheWholeRun(target);
    }

    /** Unregisters every session-scoped listener and forgets the session — shared by
     *  {@link #resetIfAbandoned} and {@link #forceReset}, the two paths that end one. */
    private void disarmSession() {
        if (occupancy != null) {
            HandlerList.unregisterAll(occupancy);
            occupancy = null;
        }
        if (creeperOnBreak != null) {
            HandlerList.unregisterAll(creeperOnBreak);
            creeperOnBreak = null;
        }
        if (creeperOnContainerOpen != null) {
            HandlerList.unregisterAll(creeperOnContainerOpen);
            creeperOnContainerOpen = null;
        }
        if (run != null) {
            // Whatever the mode hung on the run — its listeners, its compasses, its spectators —
            // goes here, on every path a run can end by, because this is the one place that knows
            // the run is over. See SpeedrunRun.
            run.disarm();
            run = null;
        }
        session = null;
    }

    /**
     * Sets the wait after which a finished run remakes its own world and the lobby is usable again —
     * see {@link #resetForAnotherRun}. Does nothing at all when the host turned that off, in which
     * case the world still resets the old way: once the last participant has left the server.
     */
    private void restartAfterFinish() {
        SpeedrunSettings current = config();
        if (!current.restartWhenRunEnds()) {
            return;
        }
        later.in(current.restartDelayTicks(), this::resetForAnotherRun);
    }

    /**
     * What the wait set by {@link #restartAfterFinish} actually does: remakes the world the run was
     * played in and puts everybody who was standing in it back into the fresh one, where the lobby
     * items are waiting for them.
     *
     * <h2>Why a whole reset rather than simply handing the items back</h2>
     * Because the items are not the point — another run is. The world a run just finished in has a
     * dead dragon in it, a looted nether and a lit portal; handing somebody a start block for that
     * map is handing them a block that refuses, because {@link #validate} would answer
     * {@code NOT_READY} for as long as the finished session exists. The reset is the thing that makes
     * the lobby ready, and the items follow from it through {@link #onReady} — which is exactly the
     * path an abandoned run already took, only without needing everybody to disconnect first.
     *
     * <h2>Why it re-checks the state it was scheduled under</h2>
     * The wait is seconds long and anything can happen in it: an admin can type
     * {@code /speedrunreset}, the last racer can quit and take {@link #resetIfAbandoned} with them,
     * the plugin can be reloaded. Whatever ran first has already done this; there is nothing left
     * here to do.
     */
    private void resetForAnotherRun() {
        if (session == null || session.state() != SpeedrunState.FINISHED) {
            return;
        }
        World target = world().orElse(null);
        // Read before the regeneration, which evacuates them: WorldRegenerator sends everybody
        // standing in a doomed world back to wherever they came from, and "wherever they came from"
        // is not the lobby they were waiting in.
        owedAWayBack.clear();
        owedAWayBack.addAll(whoIsInTheRunsWorlds());
        disarmSession();
        if (target == null) {
            log.warn("The finished run's world '{}' is not loaded; nothing to regenerate.",
                    config().worldName());
            return;
        }
        regenerateTheWholeRun(target);
    }

    /**
     * Puts everybody {@link #resetForAnotherRun} evacuated back into the world it just remade. Their
     * arrival is what hands them the lobby items — {@code SpeedrunLobbyListener.onWorldChange} — and
     * the sweep {@link #onReady} triggers catches anybody the teleport did not have to move.
     */
    private void sendBackWhoeverIsOwedAWayIn() {
        if (owedAWayBack.isEmpty()) {
            return;
        }
        World lobbyWorld = world().orElse(null);
        Location spawn = lobbyWorld == null ? null : lobbyWorld.getSpawnLocation();
        for (UUID id : Set.copyOf(owedAWayBack)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && spawn != null) {
                // teleportAsync rather than a scheduler hop: Paper takes this request from any
                // thread, and this runs on whichever one finished remaking the world.
                player.teleportAsync(spawn);
            }
        }
        owedAWayBack.clear();
    }

    /**
     * Everybody standing in any of the three worlds a run is played across — the audience for the
     * clock, and the list of people a reset owes a way back in.
     */
    private Set<UUID> whoIsInTheRunsWorlds() {
        SpeedrunWorlds worlds = SpeedrunWorlds.around(config().worldName());
        Set<UUID> found = new HashSet<>();
        for (String name : List.of(worlds.overworld(), worlds.nether(), worlds.theEnd())) {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                continue;
            }
            for (Player player : world.getPlayers()) {
                found.add(player.getUniqueId());
            }
        }
        return found;
    }

    /**
     * Who is shown the run's clock besides the racers — see
     * {@link SpeedrunTimerDisplay#alsoShowTo}. Everybody in the run's worlds, so somebody who joined
     * the server mid-run and was dropped into the lobby sees how long it has been going, rather than
     * having to ask.
     */
    private Collection<UUID> onlookers() {
        return config().showTimerToOnlookers() ? whoIsInTheRunsWorlds() : Set.of();
    }

    private Optional<World> world() {
        return Optional.ofNullable(Bukkit.getWorld(config().worldName()));
    }

    /**
     * Called once, from {@code SpeedrunModule.enable}: makes sure the configured world actually
     * exists, since nothing else here ever creates one — {@link #start}/{@link #beginCountdown} only
     * ever check whether it is already loaded. A server that never touched {@code world-name} gets
     * {@link SpeedrunSettings#DEFAULT_WORLD_NAME} for free this way, instead of a lobby that silently
     * does nothing until somebody makes that world by hand.
     *
     * <p>Never touches an already-loaded world, even the server's own primary one — creating it again
     * makes no sense, and overriding a name somebody actually configured is not this method's
     * decision to make. It only warns, once, when that configured name happens to already be the
     * primary world: every reset on it will fail, for a reason {@link WorldRegenerator} explains
     * clearly enough when it actually happens, but a warning up front is easier to notice than a log
     * line during a race.
     */
    public void ensureWorldExists() {
        String name = config().worldName();
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            if (WorldRegenerator.isPrimaryWorld(existing)) {
                log.warn("The speedrun world is set to '{}', this server's primary world. It can "
                        + "never be unloaded, so /speedrunreset and the automatic reset after a run "
                        + "will always fail on it — set world-name to a dedicated world instead.", name);
            }
        } else {
            regenerator().create(name);
        }
        // The other two dimensions of the same run. Minecraft only links these for the primary level's
        // own folder layout, never for a world made at runtime, so without them a nether portal in the
        // speedrun world drops the racer into the server's nether — and walking back out of that put
        // them in the server's overworld, outside the race entirely. See SpeedrunPortalListener.
        SpeedrunWorlds worlds = SpeedrunWorlds.around(name);
        if (Bukkit.getWorld(worlds.nether()) == null) {
            regenerator().create(worlds.nether(), World.Environment.NETHER);
        }
        if (Bukkit.getWorld(worlds.theEnd()) == null) {
            regenerator().create(worlds.theEnd(), World.Environment.THE_END);
        }
    }
}
