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

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one speedrun world: its configuration, its current {@link SpeedrunSession} if it has one, and
 * the auto-reset that happens once a finished run's last participant has left.
 *
 * <h2>Why one world, not a set of them</h2>
 * {@link SpeedrunReset} already treats "the speedrun map" as a single world with no bookkeeping of
 * its own — see its javadoc for why it does not reuse {@code FarmWorlds}' {@code WorldSet} machinery.
 * This class is the same shape one level up: a lobby has a configuration and, at most, one live
 * session, never a roster of named worlds.
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
        WORLD_MISSING
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
    private final SpeedrunReset reset;
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
    /** Who {@code /speedrunspectate} has marked as not racing — excluded from a start block's sweep of
     *  "everybody in the lobby world" until they toggle it off again. Same thread-safety reasoning as
     *  {@link #released}. */
    private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();

    /** No countdown, no finish announcement, no action-bar clock, no start-of-run reset —
     *  {@link #beginCountdown} is not usable from this alone. */
    public SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings) {
        this(plugin, settings, new SpeedrunReset(), null, null, null, null);
    }

    public SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings, BossBars bossBars,
                         Effects effects, Messages messages, ActionBars actionBars, PlayerAdmin players) {
        this(plugin, settings, new SpeedrunReset(), null, messages,
                actionBars == null ? null
                        : new SpeedrunTimerDisplay(actionBars, SpeedrunTimerDisplay.viaScheduling(plugin)),
                players == null ? null : new SpeedrunPreparation(players));
        this.countdownLauncher = (participants, onComplete) ->
                new SpeedrunCountdown(plugin, bossBars, effects, participants, onComplete, released).begin();
    }

    /** For tests: a fake {@link SpeedrunCountdownLauncher} that never touches a live server. */
    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings, SpeedrunReset reset,
                 SpeedrunCountdownLauncher countdownLauncher) {
        this(plugin, settings, reset, countdownLauncher, null, null, null);
    }

    /** For tests: exercises the finish announcement without a live server. */
    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings, SpeedrunReset reset,
                 SpeedrunCountdownLauncher countdownLauncher, Messages messages) {
        this(plugin, settings, reset, countdownLauncher, messages, null, null);
    }

    /** For tests: also exercises the action-bar clock without a live server or scheduler. */
    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings, SpeedrunReset reset,
                 SpeedrunCountdownLauncher countdownLauncher, SpeedrunTimerDisplay timerDisplay) {
        this(plugin, settings, reset, countdownLauncher, null, timerDisplay, null);
    }

    /** For tests: also exercises the start-of-run reset without a live server. */
    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings, SpeedrunReset reset,
                 SpeedrunCountdownLauncher countdownLauncher, SpeedrunPreparation preparation) {
        this(plugin, settings, reset, countdownLauncher, null, null, preparation);
    }

    SpeedrunLobby(Plugin plugin, SettingsStore<SpeedrunSettings> settings, SpeedrunReset reset,
                 SpeedrunCountdownLauncher countdownLauncher, Messages messages,
                 SpeedrunTimerDisplay timerDisplay, SpeedrunPreparation preparation) {
        this.plugin = plugin;
        this.settings = settings;
        this.reset = reset;
        this.countdownLauncher = countdownLauncher;
        this.preparation = preparation;
        this.messages = messages;
        this.timerDisplay = timerDisplay;
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
        SpeedrunSettings current = config();
        if (!current.startPointSet()) {
            return;
        }
        World target = world().orElse(null);
        if (target == null) {
            return;   // validate() has already refused a missing world; this is belt-and-braces
        }
        Location point = new Location(target, current.startX(), current.startY(), current.startZ(),
                (float) current.startYaw(), (float) current.startPitch());
        for (UUID id : participants) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.teleportAsync(point);
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
     * An admin's own escape hatch: whatever the speedrun world currently is — mid-run, freshly
     * regenerated and untouched, half-built by somebody poking around in it while READY — this ends
     * any run under way, evacuates everybody standing in the world, deletes every one of its files, and
     * makes a brand new one from scratch with a fresh random seed. Not "revert to some earlier state":
     * the old world is gone, the same way {@link SpeedrunReset#regenerate} always works.
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
        Set<UUID> evacuate = target == null ? Set.of() : target.getPlayers().stream()
                .map(Player::getUniqueId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (session != null && session.state() != SpeedrunState.FINISHED) {
            session.finish("admin-reset");
        }
        disarmSession();
        if (target == null) {
            log.warn("The speedrun world '{}' is not loaded; nothing to regenerate.", config().worldName());
            return ResetOutcome.RESET;
        }
        // Folia: see resetIfAbandoned's own note — world creation/deletion is a global-region operation.
        Scheduling.global(plugin, () -> reset.regenerate(target, SpeedrunSeed.random(), evacuate));
        return ResetOutcome.RESET;
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
        SpeedrunSession fresh = new SpeedrunSession(Set.copyOf(participants));
        if (current.hasAdvancementGoal()) {
            NamespacedKey key = NamespacedKey.fromString(current.advancementKey());
            if (key != null) {
                fresh.addEndCondition(current.isDragonKillGoal() && current.requireExitPortalAfterDragon()
                        ? new DragonExitEndCondition(plugin, key)
                        : new AdvancementEndCondition(plugin, key));
            } else {
                log.warn("'{}' is not a valid advancement key; the advancement goal was skipped.",
                        current.advancementKey());
            }
        }
        if (current.hasDeathCondition()) {
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
        if (preparation != null) {
            preparation.prepare(world().orElse(null), fresh.participants());
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
        if (!config().hasEndCondition()) {
            return StartOutcome.NO_END_CONDITION;
        }
        if (participants == null || participants.isEmpty()) {
            return StartOutcome.NO_PARTICIPANTS;
        }
        if (world().isEmpty()) {
            return StartOutcome.WORLD_MISSING;
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
        // Folia: unloading, deleting and recreating a world are global-region operations — see
        // SpeedrunReset's own threading note. resetIfAbandoned itself runs on whatever region thread
        // fired the quit event, so the reset has to hop onto the global region scheduler first rather
        // than run there directly.
        Scheduling.global(plugin, () -> reset.regenerate(target, SpeedrunSeed.random(), Set.of()));
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
        session = null;
    }

    private Optional<World> world() {
        return Optional.ofNullable(Bukkit.getWorld(config().worldName()));
    }
}
