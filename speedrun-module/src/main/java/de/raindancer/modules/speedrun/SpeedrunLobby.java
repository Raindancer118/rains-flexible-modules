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
import de.raindancer.core.world.manage.WorldSeed;
import de.raindancer.modules.speedrun.conditions.AdvancementEndCondition;
import de.raindancer.modules.speedrun.conditions.DeathEndCondition;
import de.raindancer.modules.speedrun.conditions.DragonExitEndCondition;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The one speedrun world: its configuration, its current {@link SpeedrunSession} if it has one, and
 * the auto-reset that happens once a finished run's last participant has left.
 *
 * <h2>Why one world, not a set of them</h2>
 * The speedrun map is a single world with no bookkeeping of its own — {@code WorldRegenerator} (Core's
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

    /**
     * Sets the lobby to the installed mode {@code id} — {@code /manhunt start} means a hunt whatever the
     * lobby was last set to. Only while READY or FINISHED: a run under way keeps the mode it began
     * with, and a finished one can be resumed over ({@link #resume}) in whichever mode resumes it.
     *
     * @return whether the lobby is now set to it
     */
    public boolean useMode(String id) {
        SpeedrunLobbyState now = state();
        if (SpeedrunModes.find(id).isEmpty()
                || (now != SpeedrunLobbyState.READY && now != SpeedrunLobbyState.FINISHED)) {
            return false;
        }
        if (!id.equalsIgnoreCase(config().gameMode())) {
            settings.set("game-mode", id);
        }
        return true;
    }

    /** Everybody in the lobby world who is racing — what the start block sweeps up. */
    public Set<UUID> presentInLobbyWorld() {
        World lobbyWorld = Bukkit.getWorld(config().worldName());
        if (lobbyWorld == null) {
            return Set.of();
        }
        return lobbyWorld.getPlayers().stream()
                .map(Player::getUniqueId)
                .filter(id -> !isSpectator(id))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Everybody in any of the run's three worlds who is racing — what a resume picks up. */
    public Set<UUID> presentInRunWorlds() {
        return worlds.whoIsIn().stream()
                .filter(id -> !isSpectator(id))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The wording key for {@code outcome} — the mode's own sentence for a refusal of its own, so
     * "somebody has to be running" is said rather than a generic "not right now". Sent with a
     * {@code mode} placeholder, which only MODE_MISSING uses.
     */
    public String messageFor(StartOutcome outcome, Collection<UUID> participants) {
        return switch (outcome) {
            case STARTED -> "speedrun.start.started";
            case NOT_READY -> "speedrun.start.not-ready";
            case NO_END_CONDITION -> "speedrun.start.no-end-condition";
            case NO_PARTICIPANTS -> "speedrun.start.no-participants";
            case WORLD_MISSING -> "speedrun.start.world-missing";
            case MODE_MISSING -> "speedrun.start.mode-missing";
            case MODE_FAILED -> "speedrun.start.mode-failed";
            case REFUSED_BY_MODE -> refusalFor(participants).orElse("speedrun.start.not-ready");
        };
    }

    public enum GoalRemoval { NONE_SET, REMOVED, REMOVED_FROM_RUN }

    /**
     * Clears the advancement goal — also mid-run, where the running goal is disarmed with it. The run
     * then ends by its other ways: the death policy, the mode's own end, or a reset.
     */
    public GoalRemoval removeGoal() {
        if (!config().hasAdvancementGoal()) {
            return GoalRemoval.NONE_SET;
        }
        settings.set("advancement-key", "");
        SpeedrunSession running = session;
        if (running == null || running.state() == SpeedrunState.FINISHED) {
            return GoalRemoval.REMOVED;
        }
        running.removeEndConditions(condition -> condition instanceof AdvancementEndCondition
                || condition instanceof DragonExitEndCondition);
        return GoalRemoval.REMOVED_FROM_RUN;
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
    private final SpeedrunWorldReset worlds;
    /** Not final: the public constructor below sets this itself, after delegating to the private one,
     *  because the lambda it builds reads {@link #released} — an instance field it may not reach from
     *  inside a {@code this(...)} call's own argument list. */
    private SpeedrunCountdownLauncher countdownLauncher;
    private final Messages messages;
    /** {@code null} for a lobby built without an {@link ActionBars} — the run clock is simply not shown. */
    private final SpeedrunTimerDisplay timerDisplay;
    /** {@code null} for a lobby built without a {@link PlayerAdmin} — nothing is reset before a run starts. */
    private final SpeedrunPreparation preparation;

    /** Volatile, as is {@link #countingDown}: written on whichever thread starts or resets a run, and
     *  read through {@link #state} by every region's move, damage and quit handlers under Folia. */
    private volatile SpeedrunSession session;
    /** The live run as the chosen game mode sees it — {@code null} for a plain race and between runs. */
    private volatile SpeedrunRun run;
    /** Registered fresh for every session, so a finished run's listener does not linger. */
    private SpeedrunOccupancyListener occupancy;
    /** Registered alongside {@link #occupancy}, for the same reason and on the same lifecycle. */
    private SpeedrunCreeperOnBreakListener creeperOnBreak;
    /** Registered alongside {@link #occupancy}, for the same reason and on the same lifecycle. */
    private SpeedrunCreeperOnContainerOpenListener creeperOnContainerOpen;
    /** Set the moment {@link #beginCountdown} launches one, cleared the moment it completes. */
    private volatile boolean countingDown;
    /** The world the countdown or run under way is in — see {@link #config()}. {@code null} between runs. */
    private volatile String runWorldName;
    /** The mode the run under way was started in — the finish is announced by it, whatever
     *  {@code game-mode} says by then. */
    private volatile SpeedrunMode runMode;
    /** The production launcher's countdown, so {@link #shutdown} can stop it. */
    private volatile SpeedrunCountdown activeCountdown;
    /** The run's milestones — registered with the session, gone with it. */
    private SpeedrunMilestoneListener milestones;
    /** The splits of the run under way, or of the finished one until it is reset. */
    private volatile SpeedrunSplitTracker splits;
    /** History, HUD, buttons — see {@link SpeedrunToolkit}; {@code null} for a bare lobby. */
    private volatile SpeedrunToolkit toolkit;
    private volatile SpeedrunInput input;
    /** Who joined during the countdown with late-join RACE: they race from the start like everybody else. */
    private final Set<UUID> arrivals = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Finds a safe spot for a latecomer near where they belong — Core's safety search, set by the module. */
    private volatile java.util.function.Function<Location, java.util.concurrent.CompletableFuture<Location>> latecomerSpots =
            spot -> java.util.concurrent.CompletableFuture.completedFuture(spot);
    /** The run that ended last, as the history keeps it — for the finished page's summary button. */
    private volatile SpeedrunRunRecord lastRun;
    /** "Same seed again": the next reset reuses the run's seed, once. */
    private volatile boolean replaySeed;
    private final Random seedPicker = new Random();
    /** Who {@code /lemmemove} has exempted from the READY/COUNTDOWN movement freeze — see {@link #release}.
     *  Thread-safe because the command that grants this may run on a different region thread under Folia
     *  than the move event checking it. */
    private final Set<UUID> released = ConcurrentHashMap.newKeySet();
    /** Told once the world has actually come back from a reset — see {@link #onReady}. */
    private final List<Runnable> readyListeners = new CopyOnWriteArrayList<>();
    /** How a wait is scheduled. Core's global scheduler in production; a test drives it by hand. */
    private DelayedTask later;
    /** Who {@code /speedrunspectate} has marked as not racing — excluded from a start block's sweep of
     *  "everybody in the lobby world" until they toggle it off again. Same thread-safety reasoning as
     *  {@link #released}. */
    private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();
    /** Takes the lobby items off whoever a resumed run picked up — see {@link #takeLobbyItemsWith}. */
    private volatile Consumer<UUID> lobbyItemTaker = id -> { };

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
                players == null ? null : new SpeedrunPreparation(plugin, players));
        this.countdownLauncher = (participants, onComplete) -> {
            SpeedrunCountdown countdown =
                    new SpeedrunCountdown(plugin, bossBars, effects, participants, onComplete, released);
            activeCountdown = countdown;
            countdown.begin();
        };
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
        this.worlds = new SpeedrunWorldReset(plugin, () -> config().worldName());
        this.later = (ticks, task) -> Scheduling.globalLater(plugin, ticks, task);
    }

    /**
     * Hands the lobby its history, HUD and chat buttons — called once by the module. Without it a
     * run is still a run; it is just not recorded, split on screen, or offered buttons.
     */
    public void equip(SpeedrunToolkit toolkit) {
        this.toolkit = toolkit;
        if (timerDisplay != null && toolkit != null && toolkit.hud() != null) {
            timerDisplay.alsoAppend(toolkit.hud()::actionBarSuffix);
        }
        if (toolkit != null && toolkit.history() != null) {
            toolkit.history().editedRunsRankBy(() -> config().rankEditedRuns());
        }
    }

    /** How a latecomer's spot is found — {@code spot} in, a safe one near it out (or null for none). */
    public void placeLatecomersWith(java.util.function.Function<Location, java.util.concurrent.CompletableFuture<Location>> finder) {
        this.latecomerSpots = finder == null ? spot -> java.util.concurrent.CompletableFuture.completedFuture(spot) : finder;
    }

    // ---------------------------------------------------------------------------- joining late

    /**
     * Somebody just joined the server: what {@code late-join} makes of them — see
     * {@link SpeedrunLateJoin} — done here. Called on their own thread.
     *
     * @return what they are to the lobby; {@link SpeedrunLatecomers.Arrival#RACE} and
     *         {@link SpeedrunLatecomers.Arrival#WATCH} have been placed already
     */
    public SpeedrunLatecomers.Arrival arrive(Player player) {
        UUID id = player.getUniqueId();
        SpeedrunLatecomers.Arrival arrival = SpeedrunLatecomers.decide(state(), config().lateJoinOrOff(),
                SpeedrunLatecomers.wasRacing(session, id), isSpectator(id));
        switch (arrival) {
            case NO_RUN -> standUp(player);
            case RETURNING, LOOK_ON -> { }
            case WATCH -> watch(player);
            case RACE -> race(player);
            case NEXT_START -> {
                arrivals.add(id);
                startPoint().ifPresent(player::teleportAsync);
                say(player, "speedrun.late-join.next-start");
            }
        }
        return arrival;
    }

    /** A latecomer who races: everything a racer got at the start, and a place in the run from now. */
    private void race(Player player) {
        SpeedrunSession now = session;
        UUID id = player.getUniqueId();
        if (now == null || !now.addParticipant(id)) {
            return;
        }
        SpeedrunSettings current = config();
        if (preparation != null) {
            preparation.prepareLatecomer(player, current.kit(), current.clearAdvancementsOnStart());
        }
        Scheduling.entity(plugin, player, () -> {
            SpeedrunLatecomers.WATCHING.set(player, false);
            if (player.getGameMode() == GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SURVIVAL);
            }
        });
        placeLatecomer(player);
        SpeedrunMode chosen = runMode;
        SpeedrunRun theRun = run;
        if (chosen != null && theRun != null) {
            try {
                chosen.lateJoined(theRun, player);
            } catch (RuntimeException broken) {
                log.error(broken, "The game mode '{}' failed to take in a latecomer; they race without its items.",
                        chosen.id());
            }
        }
        if (now.state() == SpeedrunState.PAUSED) {
            now.resume();   // a paused run picks up again with somebody racing in it
        }
        String time = SpeedrunTimerDisplay.plain(now.elapsed());
        say(player, "speedrun.late-join.racing", "time", time);
        for (UUID racer : now.participants()) {
            Player other = racer.equals(id) ? null : Bukkit.getPlayer(racer);
            if (other != null) {
                say(other, "speedrun.late-join.joined", "player", player.getName(), "time", time);
            }
        }
    }

    /**
     * A safe spot near the start line — or the run world's spawn without one — found off the main
     * thread, then the latecomer moved there on their own thread, and it made their respawn point the
     * way everybody's start spot is theirs.
     */
    private void placeLatecomer(Player player) {
        Location around = wayBackIn().orElse(null);
        if (around == null) {
            return;
        }
        latecomerSpots.apply(around).exceptionally(failed -> null).thenAccept(found -> {
            Location target = found == null ? around : found;
            Scheduling.entity(plugin, player, () -> {
                player.teleportAsync(target);
                player.setRespawnLocation(target, true);
            });
        });
    }

    /** A latecomer who watches: spectator mode from the start line, until the run is over. */
    private void watch(Player player) {
        Location from = wayBackIn().orElse(null);
        Scheduling.entity(plugin, player, () -> {
            SpeedrunLatecomers.WATCHING.set(player, true);
            player.setGameMode(GameMode.SPECTATOR);
            if (from != null) {
                player.teleportAsync(from);
            }
        });
        say(player, "speedrun.late-join.watching");
    }

    /** Somebody who watched a run as a latecomer stands up again — once it is over. */
    private void standUp(Player player) {
        Scheduling.entity(plugin, player, () -> {
            if (!SpeedrunLatecomers.WATCHING.isOn(player)) {
                return;
            }
            SpeedrunLatecomers.WATCHING.set(player, false);
            if (player.getGameMode() == GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SURVIVAL);
            }
        });
    }

    /** Everybody online who watched the run that just ended stands up. */
    private void standWatchersUp() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            standUp(player);
        }
    }

    private void say(Player player, String key, Object... values) {
        if (messages != null && player != null) {
            messages.send(player, key, values);
        }
    }

    /** How values are asked for from here on — tests answer for the player. */
    public void useInput(SpeedrunInput chosen) {
        this.input = chosen;
    }

    /** How values are asked for: Core's anvil and chat questions, once the lobby is equipped. */
    public Optional<SpeedrunInput> input() {
        SpeedrunInput chosen = input;
        if (chosen != null) {
            return Optional.of(chosen);
        }
        SpeedrunToolkit kit = toolkit;
        return kit == null || kit.prompts() == null ? Optional.empty()
                : Optional.of(SpeedrunInput.core(kit.prompts(), kit.buttons(), kit.messages()));
    }

    public Optional<SpeedrunToolkit> toolkit() {
        return Optional.ofNullable(toolkit);
    }

    /** The splits of the run under way — or of the finished one, until it is reset. */
    public Optional<SpeedrunSplitTracker> splits() {
        return Optional.ofNullable(splits);
    }

    /** The run that ended last, as kept in the history — empty before any ended since the start. */
    public Optional<SpeedrunRunRecord> lastRun() {
        return Optional.ofNullable(lastRun);
    }

    /** "Same seed again": the next reset remakes the run's worlds from the seed they have now. */
    public void replaySeedNextReset() {
        replaySeed = true;
    }

    /** Whether the next reset will reuse the seed — shown on the seed page. */
    public boolean replayingSeed() {
        return replaySeed;
    }

    /** The seed the next reset will use, and forgets a one-off "same seed again". */
    WorldSeed nextSeed() {
        if (replaySeed) {
            replaySeed = false;
            return WorldSeed.same();
        }
        return SpeedrunSeeds.next(config(), seedPicker);
    }

    /** For tests: runs the waits by hand instead of through Paper's scheduler. */
    void schedulesLaterWith(DelayedTask later) {
        this.later = later;
    }

    /** The plugin this lobby runs inside. */
    public Plugin plugin() {
        return plugin;
    }

    /**
     * The settings as they stand — except the world, which is the run's own from the moment a
     * countdown begins until the run is reset or forgotten. {@code world-name} changed in
     * {@code /settings} mid-run takes effect for the next run; until then every listener, the portal
     * and respawn redirects and, above all, the reset keep meaning the world the run is played in.
     * A reset pointed at the newly named world would delete a world nobody raced in.
     */
    public SpeedrunSettings config() {
        SpeedrunSettings current = settings.current();
        String pinned = runWorldName;
        return pinned == null || pinned.equals(current.worldName()) ? current : current.withWorldName(pinned);
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
        StartOutcome problem = validate(participants, false);
        if (problem != null) {
            return problem;
        }
        if (countdownLauncher == null) {
            log.error("beginCountdown() was called on a SpeedrunLobby built without a countdown "
                    + "launcher — that constructor is for tests only.");
            return StartOutcome.NOT_READY;
        }
        Set<UUID> frozen = Set.copyOf(participants);
        runWorldName = settings.current().worldName();
        teleportToStartPoint(frozen);
        countingDown = true;
        countdownLauncher.begin(frozen, () -> {
            countingDown = false;
            countdownReachedZero(frozen);
        });
        return StartOutcome.STARTED;
    }

    /**
     * The second validation, at zero. The menu, {@code /settings} and a mode's own commands stay
     * usable through the countdown, so a start that was fine five seconds ago can be refused now — and
     * the racers' lobby items were taken when the countdown began. A refusal therefore hands the lobby
     * back the way a failed mode start does, and tells the racers why rather than leaving them frozen
     * in thought with empty hands.
     */
    private void countdownReachedZero(Set<UUID> frozen) {
        activeCountdown = null;
        // Whoever left during the countdown does not race: nothing of an offline player can be
        // prepared, so they would come back with last round's gear — and with nobody online at all the
        // clock would run for nobody.
        Set<UUID> stillHere = new HashSet<>();
        for (UUID id : frozen) {
            if (Bukkit.getPlayer(id) != null) {
                stillHere.add(id);
            }
        }
        // Whoever joined during the countdown with late-join RACE races from the start too.
        for (UUID id : arrivals) {
            if (Bukkit.getPlayer(id) != null && !isSpectator(id)) {
                stillHere.add(id);
            }
        }
        arrivals.clear();
        StartOutcome outcome = start(stillHere);
        if (outcome == StartOutcome.STARTED) {
            return;
        }
        if (session == null) {
            runWorldName = null;
        }
        log.info("The countdown ended but the run did not start ({}); the lobby is ready again.", outcome);
        if (messages != null) {
            String key = messageFor(outcome, frozen);
            for (UUID id : frozen) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    messages.send(player, key, "mode", config().gameMode());
                }
            }
        }
        if (outcome != StartOutcome.MODE_FAILED) {
            announceReady();   // a failed mode start has already handed the lobby back
        }
    }

    private void teleportToStartPoint(Set<UUID> participants) {
        // Empty when /starthere never ran — nobody is moved then, which is the documented "off" —
        // unless the game mode places people itself (Manhunt's circle), around the world's spawn.
        Location point = startPoint().orElse(null);
        Map<UUID, Location> placed = mode()
                .map(chosen -> {
                    Location centre = point != null ? point
                            : world().map(World::getSpawnLocation).orElse(null);
                    return centre == null ? Map.<UUID, Location>of()
                            : chosen.startingSpots(centre, participants);
                })
                .orElse(Map.of());
        for (UUID id : participants) {
            Location target = placed.getOrDefault(id, point);
            if (target == null) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.teleportAsync(target);
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
        worlds.sendBackWhoeverIsOwed();
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
     * Forgets whatever {@code /starthere} set — called by every reset that actually remakes the
     * world, from {@link #resetTheRun}.
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
     * any run under way and hands the world to Core's {@code WorldRegenerator}: everybody standing
     * in it is evacuated (back to wherever they were before they arrived, not a generic spawn — see
     * {@code WorldEntryPoints}), every one of its files is deleted, and a brand new world is made from
     * scratch. Not "revert to some earlier state": the old world is gone.
     *
     * <p>Everybody standing in any of the run's three worlds is put back into the fresh lobby once it
     * is up, exactly as {@link #resetForAnotherRun} does for a run that ended on its own — an admin
     * resetting the map and a Runner dying end in the same place.
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
        if (session != null && session.state() != SpeedrunState.FINISHED) {
            session.finish("admin-reset");
        }
        resetTheRun();
        return ResetOutcome.RESET;
    }

    /**
     * The one way every reset — an admin's, a finished run's wait, the last racer leaving — forgets
     * the run and remakes its worlds.
     *
     * <p>Who is standing in the run's worlds is read before the regeneration, which evacuates
     * everybody to wherever they entered the world from rather than to the lobby, and they are put
     * back into the fresh one once it is up. Without that an admin's reset left the people waiting
     * for the next run scattered outside it, while the identical reset after a death put them back
     * on the start line with the items in their hands; reported as exactly that difference.
     */
    private void resetTheRun() {
        World target = world().orElse(null);   // the run's world, while it is still pinned
        String runWorld = config().worldName();
        worlds.rememberWhoIsIn();
        disarmSession();
        if (target == null) {
            log.warn("The speedrun world '{}' is not loaded; nothing to regenerate.", runWorld);
            return;
        }
        // Before anything else: the point /starthere set is coordinates in the world about to be
        // deleted, and the one that comes back is a different world under the same name.
        clearStartPoint();
        worlds.regenerate(target, nextSeed(), () -> {
            if (!runWorld.equals(config().worldName())) {
                // world-name was changed during the run: the next one is played in the new world,
                // which nothing has made yet.
                worlds.ensureExists(nextSeed());
            }
            announceReady();
        });
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
        return start(participants, false, Duration.ZERO);
    }

    /**
     * {@code /speedrunresume}: a run started over a world already being played — after a restart lost
     * the old one. No countdown, nobody moved, healed or cleared, the world's time and mobs left alone,
     * and the clock starting at {@code already}. The lobby items are taken back, nothing else.
     */
    public StartOutcome resume(Collection<UUID> participants, Duration already) {
        return start(participants, true, already);
    }

    /** Takes the lobby items off whoever a resumed run picked up — set by the module, which has them. */
    public void takeLobbyItemsWith(Consumer<UUID> taker) {
        this.lobbyItemTaker = taker == null ? id -> { } : taker;
    }

    private StartOutcome start(Collection<UUID> participants, boolean resumed, Duration already) {
        StartOutcome problem = validate(participants, resumed);
        if (problem != null) {
            return problem;
        }
        if (state() == SpeedrunLobbyState.FINISHED) {
            // Only a resume gets here. A finished run is otherwise only cleared by regenerating the
            // world — the very thing a resume is for avoiding — and its pending automatic reset is
            // bound to it, so forgetting it here also defuses that.
            disarmSession();
        }
        SpeedrunSettings current = config();
        SpeedrunMode chosen = mode().orElse(null);
        SpeedrunSession fresh = new SpeedrunSession(Set.copyOf(participants));
        SpeedrunWorlds runWorlds = SpeedrunWorlds.around(current.worldName());
        // Who reaching the goal actually ends the run. Every participant in a plain race; in Manhunt
        // only a Runner, since a Hunter killing the dragon has won the Runners nothing. Asked of the
        // mode at the moment it happens rather than snapshotted here, so a side changing mid-run —
        // which Manhunt does not allow, but another mode might — is answered as it stands then.
        Predicate<UUID> countsForGoal =
                chosen == null ? participant -> true : chosen::countsForGoal;
        if (current.hasAdvancementGoal()) {
            NamespacedKey key = NamespacedKey.fromString(current.advancementKey());
            if (key != null) {
                fresh.addEndCondition(current.isDragonKillGoal() && current.requireExitPortalAfterDragon()
                        ? dragonExit(key, countsForGoal, runWorlds, resumed)
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
        runWorldName = current.worldName();
        runMode = chosen;
        SpeedrunSplitTracker tracker = new SpeedrunSplitTracker(fresh);
        splits = tracker;
        World playedIn = world().orElse(null);
        long seed = playedIn == null ? 0L : playedIn.getSeed();
        long startedAt = System.currentTimeMillis();
        SpeedrunToolkit kit = toolkit;
        SpeedrunHistory history = kit == null ? null : kit.history();
        SpeedrunCategory category = new SpeedrunCategory(
                current.hasAdvancementGoal() ? current.advancementKey() : "",
                SpeedrunSeeds.typeOf(seed, current, history),
                chosen == null ? "" : chosen.id(),
                current.kit().isPractice() ? current.kit().name() : "");
        tracker.compareWith(history, category);
        if (resumed) {
            fresh.timeline().record(SpeedrunTimeline.Kind.RESUMED, already, null, "");
        }
        milestones = new SpeedrunMilestoneListener(fresh, tracker, runWorlds, () -> config().pearlsToCollect(),
                countsForGoal);
        plugin.getServer().getPluginManager().registerEvents(milestones, plugin);
        if (kit != null) {
            SpeedrunSplitAnnouncer announcer = new SpeedrunSplitAnnouncer(kit, this::config, fresh, tracker,
                    this::onlookers, chosen != null && chosen.titlesOnSplits());
            tracker.onSplit(announcer::announce);
        }
        occupancy = new SpeedrunOccupancyListener(fresh);
        plugin.getServer().getPluginManager().registerEvents(occupancy, plugin);
        creeperOnBreak = new SpeedrunCreeperOnBreakListener(fresh, settings);
        plugin.getServer().getPluginManager().registerEvents(creeperOnBreak, plugin);
        creeperOnContainerOpen = new SpeedrunCreeperOnContainerOpenListener(fresh, settings);
        plugin.getServer().getPluginManager().registerEvents(creeperOnContainerOpen, plugin);
        fresh.onFinish(outcome -> announceFinish(fresh, outcome));
        fresh.onFinish(outcome -> standWatchersUp());
        fresh.onFinish(outcome -> restartAfterFinish(fresh));
        if (kit != null) {
            SpeedrunRunRecorder recorder = new SpeedrunRunRecorder(this, kit);
            fresh.onFinish(outcome -> lastRun = recorder.record(fresh, tracker, outcome, category, seed, startedAt,
                    chosen == null ? outcome.reason() != null && outcome.reason().startsWith("advancement:")
                            : chosen.leaderboardEligible(outcome), chosen));
        }
        if (preparation != null && !resumed) {
            preparation.prepare(world().orElse(null), fresh.participants(), current.timeAtStart(),
                    current.clearAdvancementsOnStart());
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
        // A resumed run keeps whatever respawn point each of them had — their bed is part of the game.
        for (UUID id : resumed ? Set.<UUID>of() : fresh.participants()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Scheduling.entity(plugin, player,
                        () -> player.setRespawnLocation(player.getLocation(), true));
            }
        }
        if (resumed) {
            fresh.participants().forEach(lobbyItemTaker);
        } else if (preparation != null && current.kit().isPractice()) {
            preparation.handOut(fresh.participants(), current.kit());
        }
        if (chosen != null) {
            SpeedrunRun theRun = new SpeedrunRun(plugin, fresh, SpeedrunWorlds.around(current.worldName()),
                    resumed, tracker);
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
        if (kit != null && kit.hud() != null) {
            kit.hud().start(fresh, tracker, this::onlookers);
        }
        fresh.start(already);
        return StartOutcome.STARTED;
    }

    /**
     * Tells every participant still online what ended the run and how long it took — the confirmation
     * that they raced under the settings they saw in the lobby menu, not a silent state change nobody
     * but the boss bar noticed.
     */
    private void announceFinish(SpeedrunSession finished, SpeedrunOutcome outcome) {
        SpeedrunMode chosen = runMode;
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
        String time = SpeedrunTimerDisplay.plain(outcome.elapsed());
        for (UUID participant : finished.participants()) {
            Player player = Bukkit.getPlayer(participant);
            if (player != null) {
                messages.send(player, "speedrun.finished", "reason", reason, "time", time);
            }
        }
    }

    /** {@code "advancement:minecraft:end/kill_dragon"} → the advancement's own display name, and so on. */
    static String friendlyReason(String reason) {
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

    /**
     * @param overAFinishedRun whether a FINISHED lobby counts as ready — a resume's question, since
     *                         nothing else may start over a finished run without a reset first
     * @return the reason a run cannot start right now, or {@code null} when it can
     */
    private StartOutcome validate(Collection<UUID> participants, boolean overAFinishedRun) {
        SpeedrunLobbyState now = state();
        if (now != SpeedrunLobbyState.READY
                && !(overAFinishedRun && now == SpeedrunLobbyState.FINISHED)) {
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
                : current.hasAdvancementGoal() || chosen.endsItself();
        if (!endable) {
            return StartOutcome.NO_END_CONDITION;
        }
        // The world before the roster: with it unloaded nobody can be "present", and "nobody is
        // here" would send an admin looking for the wrong problem.
        if (world().isEmpty()) {
            return StartOutcome.WORLD_MISSING;
        }
        if (participants == null || participants.isEmpty()) {
            return StartOutcome.NO_PARTICIPANTS;
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
     * <p>Not while the server is stopping or this plugin is being disabled: being kicked by a shutdown
     * is not "everybody left", and a world deleted on the way down is a world the next start cannot
     * resume in.
     *
     * @param quitting the player who just quit, so they can be excluded from "is anybody still here"
     */
    public void resetIfAbandoned(UUID quitting) {
        if (state() != SpeedrunLobbyState.FINISHED || Bukkit.isStopping() || !plugin.isEnabled()) {
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
        resetTheRun();
    }

    /** Unregisters every session-scoped listener and forgets the session — shared by every path that
     *  ends one: the resets, a failed mode start, and a resume over a finished run. */
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
        if (milestones != null) {
            HandlerList.unregisterAll(milestones);
            milestones = null;
        }
        splits = null;
        SpeedrunToolkit kit = toolkit;
        if (kit != null && kit.hud() != null) {
            kit.hud().stop();
        }
        if (run != null) {
            // Whatever the mode hung on the run — its listeners, its compasses, its spectators —
            // goes here, on every path a run can end by, because this is the one place that knows
            // the run is over. See SpeedrunRun.
            run.disarm();
            run = null;
        }
        session = null;
        runMode = null;
        runWorldName = null;
        arrivals.clear();
        standWatchersUp();
    }

    /**
     * The exit-portal goal, counting only the run's own End. A resumed run may be picked up after
     * the dragon already died — the fight is won, the portal open — and is then armed as such, or it
     * could never be won. An ordinary start never assumes it: its End was remade by the last reset.
     */
    private DragonExitEndCondition dragonExit(NamespacedKey key, Predicate<UUID> countsForGoal,
                                              SpeedrunWorlds runWorlds, boolean resumed) {
        DragonExitEndCondition condition = new DragonExitEndCondition(plugin, key, countsForGoal, runWorlds);
        if (resumed) {
            World end = Bukkit.getWorld(runWorlds.theEnd());
            DragonBattle battle = end == null ? null : end.getEnderDragonBattle();
            if (battle != null && battle.hasBeenPreviouslyKilled()) {
                condition.dragonAlreadyKilled();
            }
        }
        return condition;
    }

    /**
     * The plugin is going away ({@code SpeedrunModule.disable}). Stops a countdown, takes the clock
     * off everybody's action bar — both live in Core, which outlives this plugin, so neither would
     * ever be cleared otherwise — and lets the game mode clean up after its run. Never touches the
     * world: a disable is not a reset, and the run can be picked up with {@code /speedrunresume}.
     */
    public void shutdown() {
        SpeedrunCountdown countdown = activeCountdown;
        if (countdown != null) {
            countdown.cancel();
            activeCountdown = null;
        }
        countingDown = false;
        if (timerDisplay != null) {
            timerDisplay.clear();
        }
        disarmSession();
    }

    /**
     * Sets the wait after which a finished run remakes its own world and the lobby is usable again —
     * see {@link #resetForAnotherRun}. Does nothing at all when the host turned that off, in which
     * case the world still resets the old way: once the last participant has left the server.
     */
    private void restartAfterFinish(SpeedrunSession finished) {
        SpeedrunSettings current = config();
        if (!current.restartWhenRunEnds()) {
            return;
        }
        later.in(current.restartDelayTicks(), () -> resetForAnotherRun(finished));
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
     * <h2>Why it re-checks the run it was scheduled for</h2>
     * The wait is seconds long and anything can happen in it: an admin can type
     * {@code /speedrunreset}, the last racer can quit and take {@link #resetIfAbandoned} with them, a
     * resume can replace the finished run with a new one — which may itself have finished by the time
     * this comes up, and then has its own wait. Only the very run this was scheduled for, still
     * finished and still the lobby's, is reset here.
     */
    private void resetForAnotherRun(SpeedrunSession finished) {
        if (session != finished || finished.state() != SpeedrunState.FINISHED) {
            return;
        }
        resetTheRun();
    }

    /**
     * Who is shown the run's clock besides the racers — see
     * {@link SpeedrunTimerDisplay#alsoShowTo}. Everybody in the run's worlds, so somebody who joined
     * the server mid-run and was dropped into the lobby sees how long it has been going, rather than
     * having to ask.
     */
    private Collection<UUID> onlookers() {
        return config().showTimerToOnlookers() ? worlds.whoIsIn() : Set.of();
    }

    private Optional<World> world() {
        return Optional.ofNullable(Bukkit.getWorld(config().worldName()));
    }

    /** Called once, from {@code SpeedrunModule.enable} — see {@link SpeedrunWorldReset#ensureExists}. */
    public void ensureWorldExists() {
        worlds.ensureExists(SpeedrunSeeds.next(config(), seedPicker));
    }
}
