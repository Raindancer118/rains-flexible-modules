package de.raindancer.modules.manhunt.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.conditions.AllRunnersDeadEndCondition;
import de.raindancer.modules.manhunt.conditions.RunnerAdvancementEndCondition;
import de.raindancer.modules.manhunt.conditions.RunnerExitEndCondition;
import de.raindancer.modules.manhunt.conditions.TimeoutEndCondition;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.speedrun.SpeedrunEndCondition;
import de.raindancer.modules.speedrun.SpeedrunOccupancyListener;
import de.raindancer.modules.speedrun.SpeedrunOutcome;
import de.raindancer.modules.speedrun.SpeedrunReset;
import de.raindancer.modules.speedrun.SpeedrunSeed;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Runs exactly one Manhunt at a time: builds the {@link SpeedrunSession} with whichever end
 * conditions {@link ManhuntSettings} currently names on each side, holds the Hunters for their head
 * start, and resets the map between attempts.
 *
 * <h2>Why one run, not a map of them like {@code ChainService}</h2>
 * A chained pair is one of potentially many independent pairs on the same server, each racing on its
 * own; a Manhunt roster is the whole thing — every Runner against every Hunter, one hunt at a time.
 * {@link ManhuntTeams} already refuses a third team, so there is exactly one Runner side and one
 * Hunter side to ever have a run.
 */
public final class ManhuntService {

    /** What {@link #start} answered. */
    public enum StartOutcome {
        STARTED, ALREADY_RUNNING, NO_RUNNERS, NO_HUNTERS, WORLD_MISSING,
        /**
         * The Hunters win when every Runner is out, and the Runners respawn forever — so nobody can
         * ever be out, and one side could never win. Refused rather than started, because a hunt
         * like that looks perfectly normal right up until the moment it matters.
         */
        HUNTERS_CANNOT_WIN
    }

    /** Runs {@code task} once a second until told to stop — see {@code ChainService}'s own copy. */
    @FunctionalInterface
    public interface RunTicker {
        AutoCloseable everySecond(Runnable task);
    }

    public static RunTicker viaScheduling(Plugin plugin) {
        return task -> {
            var scheduled = Scheduling.globalTimer(plugin, 20L, 20L, handle -> task.run());
            return scheduled::cancel;
        };
    }

    /** Never actually schedules anything. For tests, which drive a tick themselves. */
    public static RunTicker manual() {
        return task -> () -> { };
    }

    /**
     * The seconds between {@link #start} being asked for and the hunt actually beginning. A seam for
     * the same reason {@link RunTicker} is one: a countdown is a scheduler and a line a second, and every
     * decision around it is testable only if the waiting itself can be taken out.
     */
    @FunctionalInterface
    public interface RunCountdown {
        /** Counts {@code seconds} down in front of {@code participants}, then runs {@code onDone}. */
        void count(Set<UUID> participants, int seconds, Runnable onDone);
    }

    /** Never waits at all — runs the hunt immediately. For tests, and for a countdown of zero. */
    public static RunCountdown immediate() {
        return (participants, seconds, onDone) -> onDone.run();
    }

    private final Plugin plugin;
    private final ManhuntTeams teams;
    private final Messages messages;
    private final SpeedrunReset reset;
    private final RunTicker ticker;
    private final RunCountdown countdown;
    private final ManhuntLives lives;

    private volatile ManhuntSettings settings;

    private Consumer<Set<UUID>> onStart = roster -> { };
    private BiConsumer<Set<UUID>, SpeedrunOutcome> onFinished = (roster, outcome) -> { };

    private SpeedrunSession session;
    /** True from the moment a countdown begins until the hunt it belongs to is over — see
     *  {@link #isRunning()}, which a second {@code /manhunt start} and the roster freeze both ask. */
    private volatile boolean counting;
    private SpeedrunOccupancyListener occupancy;
    private HunterHoldListener hold;
    private AutoCloseable ticking;

    public ManhuntService(Plugin plugin, ManhuntTeams teams, Messages messages, ManhuntSettings settings) {
        this(plugin, teams, messages, new SpeedrunReset(), viaScheduling(plugin),
                ManhuntCountdown.viaScheduling(plugin, messages), settings);
    }

    /** The same, with the world-reset step, the ticker and the countdown injectable — what the
     *  tests use. */
    ManhuntService(Plugin plugin, ManhuntTeams teams, Messages messages,
                  SpeedrunReset reset, RunTicker ticker, RunCountdown countdown,
                  ManhuntSettings settings) {
        this.plugin = plugin;
        this.teams = teams;
        this.messages = messages;
        this.reset = reset;
        this.ticker = ticker;
        this.countdown = countdown;
        this.lives = new ManhuntLives(settings);
        settings(settings);
    }

    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
        if (lives != null) {
            lives.settings(fresh);
        }
    }

    /** Who is still standing, and what a death costs them — see {@link ManhuntLives}. */
    public ManhuntLives lives() {
        return lives;
    }

    public ManhuntSettings config() {
        return settings;
    }

    public ManhuntTeams teams() {
        return teams;
    }

    /** Whether the roster may currently change sides — {@link ManhuntTeams}' own "fact about the moment". */
    public boolean isRunning() {
        return counting || (session != null && session.state() != SpeedrunState.FINISHED);
    }

    public Optional<SpeedrunSession> session() {
        return Optional.ofNullable(session);
    }

    /**
     * Told the full roster right as a run actually starts — how {@code ManhuntAchievements} learns to
     * award {@code first-hunt} without this class knowing achievements exist. One hook, not a list:
     * nothing in this module needs more than one caller wired at a time, and a caller stacking two
     * concerns behind the same moment is the caller's own composition to do, not this class'.
     */
    public void onStart(Consumer<Set<UUID>> hook) {
        this.onStart = hook != null ? hook : roster -> { };
    }

    /** Told the roster and the {@link SpeedrunOutcome} the moment a run finishes — see {@link #onStart}. */
    public void onFinished(BiConsumer<Set<UUID>, SpeedrunOutcome> hook) {
        this.onFinished = hook != null ? hook : (roster, outcome) -> { };
    }

    // ------------------------------------------------------------------------ running

    public StartOutcome start() {
        if (isRunning()) {
            return StartOutcome.ALREADY_RUNNING;
        }
        Set<UUID> runners = teams.runners();
        Set<UUID> hunters = teams.hunters();
        if (runners.isEmpty()) {
            return StartOutcome.NO_RUNNERS;
        }
        if (hunters.isEmpty()) {
            return StartOutcome.NO_HUNTERS;
        }

        ManhuntSettings config = settings;
        // Reported live: a Runner died, the hunt carried on, and the death line said they had
        // 2147483646 lives left. That is RESPAWN, which never puts a Runner out, against
        // ALL_RUNNERS_DEAD, which waits for every Runner to be out. The two settings live on
        // different pages and each is reasonable alone; together they are a hunt the Hunters cannot
        // win, and nothing said so. Checked before anything is touched — no world reset, no freeze.
        if (config.hunterWin() == ManhuntSettings.HunterWinCondition.ALL_RUNNERS_DEAD
                && config.runnerDeathRule() == ManhuntSettings.RunnerDeathRule.RESPAWN) {
            return StartOutcome.HUNTERS_CANNOT_WIN;
        }
        World world = plugin.getServer().getWorld(config.worldName());
        if (world == null) {
            return StartOutcome.WORLD_MISSING;
        }
        if (config.resetOnStart()) {
            // Fire-and-forget, the same way ChainService.start() does: the world Manhunt keeps
            // playing on either way is the one already loaded under this name, and waiting for the
            // regenerate callback here would mean every /manhunt start blocking on the caller's
            // thread for however long deleting and recreating a world folder takes.
            Scheduling.global(plugin, () -> reset.regenerate(world, defaultSeed(config), teams.everybody()));
        }

        Set<UUID> everybody = teams.everybody();
        int seconds = config.countdownSecondsClamped();
        if (seconds <= 0) {
            begin(config, runners, hunters, everybody);
            return StartOutcome.STARTED;
        }
        // Counting, not yet running: the roster is already frozen (see isRunning) so nobody can
        // switch sides during the count, and a second /manhunt start is refused rather than starting
        // a parallel hunt behind the first one's countdown.
        counting = true;
        countdown.count(everybody, seconds, () -> {
            counting = false;
            // Re-read: an owner may have changed something during the count, and the roster itself
            // cannot have moved because the freeze above held it.
            begin(settings, teams.runners(), teams.hunters(), teams.everybody());
        });
        return StartOutcome.STARTED;
    }

    /** The hunt itself, once whatever countdown there was has run out. */
    private void begin(ManhuntSettings config, Set<UUID> runners, Set<UUID> hunters, Set<UUID> everybody) {
        lives.reset();
        SpeedrunSession fresh = new SpeedrunSession(everybody);
        for (SpeedrunEndCondition condition : conditionsFor(config, runners)) {
            fresh.addEndCondition(condition);
        }
        fresh.onFinish(outcome -> {
            announceFinish(everybody, outcome);
            onFinished.accept(everybody, outcome);
            endRun();
        });

        session = fresh;
        occupancy = new SpeedrunOccupancyListener(fresh);
        plugin.getServer().getPluginManager().registerEvents(occupancy, plugin);

        // Before the head start's hold is registered, and before anybody is loose. Teleports raise
        // PlayerTeleportEvent, which has its own handler list, so neither the countdown's freeze nor
        // the hold (both PlayerMoveEvent) can cancel these.
        arrangeInCircle(config, runners, hunters);

        int delaySeconds = config.hunterReleaseDelaySecondsClamped();
        if (delaySeconds > 0) {
            hold = new HunterHoldListener(hunters);
            plugin.getServer().getPluginManager().registerEvents(hold, plugin);
            Scheduling.globalLater(plugin, delaySeconds * 20L, this::releaseHunters);
        }

        fresh.start();
        ticking = ticker.everySecond(this::tick);
        resetParticipants(everybody);
        onStart.accept(everybody);
    }

    /**
     * Every participant who is online gets a clean slate the moment a hunt actually starts: full
     * health and hunger, no leftover potion effects from whatever happened in the waiting lobby or an
     * earlier attempt, and out of Adventure mode if the waiting lobby (see {@code ManhuntLobbyBox})
     * left them in it. A no-op in these tests, since the mocked {@code Server} answers null for every
     * id without a real one behind it — {@code plugin.getServer().getPlayer(id)} rather than the
     * static {@code Bukkit.getPlayer(id)}, matching how every other lookup in this class already
     * reaches Bukkit, and the only way to ask without every test needing its own
     * {@code mockStatic(Bukkit.class)} just for this loop.
     */
    private void resetParticipants(Set<UUID> everybody) {
        for (UUID id : everybody) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) {
                continue;
            }
            dropSpeedrunLobbyItems(player);
            var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                player.setHealth(maxHealth.getValue());
            }
            player.setFoodLevel(20);
            player.setSaturation(20f);
            player.setExhaustion(0f);
            for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
                player.removePotionEffect(effect.getType());
            }
            if (player.getGameMode() == GameMode.ADVENTURE) {
                player.setGameMode(GameMode.SURVIVAL);
            }
        }
    }

    /** The gap between neighbours on the starting circle, and the smallest circle there is. */
    private static final double CIRCLE_SPACING = 4;
    private static final double CIRCLE_MIN_RADIUS = 5;

    /**
     * Puts every online participant evenly around one circle at the hunt world's spawn, facing the
     * middle — asked for directly. Runners first and Hunters after, so each side stands together
     * rather than interleaved. The circle's size follows the roster, see {@link SpawnCircle}.
     *
     * <p>Package-private for the tests: the rest of {@code begin()} resets every participant through
     * {@code Attribute.MAX_HEALTH}, which cannot be resolved without a server.
     */
    void arrangeInCircle(ManhuntSettings config, Set<UUID> runners, Set<UUID> hunters) {
        if (!config.startInCircle()) {
            return;
        }
        World world = plugin.getServer().getWorld(config.worldName());
        if (world == null) {
            return;
        }
        org.bukkit.Location centre = world.getSpawnLocation();
        if (centre == null) {
            return;
        }
        List<Player> placing = new java.util.ArrayList<>();
        for (UUID id : runners) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                placing.add(player);
            }
        }
        for (UUID id : hunters) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                placing.add(player);
            }
        }
        List<SpawnCircle.Spot> spots = SpawnCircle.around(centre.getX(), centre.getZ(), placing.size(),
                CIRCLE_SPACING, CIRCLE_MIN_RADIUS);
        for (int i = 0; i < placing.size(); i++) {
            SpawnCircle.Spot spot = spots.get(i);
            int blockX = (int) Math.floor(spot.x());
            int blockZ = (int) Math.floor(spot.z());
            // The middle of the block, one above whatever is highest there — on the ground, never
            // inside it, whatever the terrain around the spawn happens to be.
            org.bukkit.Location at = new org.bukkit.Location(world, blockX + 0.5,
                    world.getHighestBlockYAt(blockX, blockZ) + 1, blockZ + 0.5, spot.yaw(), 0f);
            placing.get(i).teleportAsync(at);
        }
    }

    /**
     * Takes speedrun-module's lobby compass and start block off a participant.
     *
     * <p>Found with two real clients: on a server running RainsSpeedrun, everybody who joined into its
     * lobby is carrying both when a hunt begins. In a hunt they are worse than useless — a second
     * compass that points nowhere, beside the tracking compass a Hunter is looking for, and a block
     * that starts a speedrun. Recognised by the key's name, whatever plugin speedrun-module runs
     * inside; {@code SpeedrunLobbyItems.MARKER_KEY} is a compile-time constant, so this needs no newer
     * RainsSpeedrun at runtime.
     */
    static void dropSpeedrunLobbyItems(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isSpeedrunLobbyItem(contents[slot])) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    static boolean isSpeedrunLobbyItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().getKeys().stream()
                .anyMatch(key -> de.raindancer.modules.speedrun.SpeedrunLobbyItems.MARKER_KEY.equals(key.getKey()));
    }

    private void releaseHunters() {
        if (hold != null) {
            HandlerList.unregisterAll(hold);
            hold = null;
        }
    }

    private List<SpeedrunEndCondition> conditionsFor(ManhuntSettings config, Set<UUID> runners) {
        SpeedrunEndCondition runnerSide = switch (config.runnerWin()) {
            case PORTAL_EXIT -> new RunnerExitEndCondition(plugin, runners);
            case ADVANCEMENT -> new RunnerAdvancementEndCondition(plugin, runnerAdvancementKey(config), runners);
        };
        SpeedrunEndCondition hunterSide = switch (config.hunterWin()) {
            case ALL_RUNNERS_DEAD -> new AllRunnersDeadEndCondition(plugin, runners, lives);
            case TIMEOUT -> new TimeoutEndCondition(plugin, Duration.ofMinutes(config.hunterTimeoutMinutesClamped()));
        };
        List<SpeedrunEndCondition> armed = new java.util.ArrayList<>(List.of(runnerSide, hunterSide));
        // Under TIMEOUT, nothing above watches the Runners dying — and a hunt whose every Runner is
        // eliminated is over whatever the clock says, since there is nobody left who could still win
        // it. Never armed twice: under ALL_RUNNERS_DEAD the condition above is already that watcher.
        if (config.hunterWin() != ManhuntSettings.HunterWinCondition.ALL_RUNNERS_DEAD
                && config.runnerDeathRule() != ManhuntSettings.RunnerDeathRule.RESPAWN) {
            armed.add(new AllRunnersDeadEndCondition(plugin, runners, lives));
        }
        return List.copyOf(armed);
    }

    private static NamespacedKey runnerAdvancementKey(ManhuntSettings config) {
        NamespacedKey key = NamespacedKey.fromString(config.runnerAdvancementKey());
        return key != null ? key : NamespacedKey.minecraft("end/kill_dragon");
    }

    /** Ends the run early, for a reason other than one of the configured win conditions firing. */
    public boolean stop() {
        if (!isRunning()) {
            return false;
        }
        session.finish("manual");
        return true;
    }

    /**
     * Once a second, only to notice a session that finished on its own. There is no boss bar: it was
     * taken out entirely, asked for directly — the clock is on the action bar
     * ({@code SpeedrunTimerDisplay}, wired in {@code ManhuntModule}) and chat says what happened.
     */
    private void tick() {
        if (session == null) {
            return;
        }
        if (session.state() == SpeedrunState.FINISHED) {
            endRun();
        }
    }

    /** Tells everybody still online what ended the hunt and how long it took — Runner or Hunter,
     *  the whole roster hears the same line, worded from {@code manhunt.finished}. */
    private void announceFinish(Set<UUID> everybody, SpeedrunOutcome outcome) {
        if (messages == null) {
            return;
        }
        String time = formatted(outcome.elapsed());
        for (UUID id : everybody) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                messages.send(player, "manhunt.finished", "reason", outcome.reason(), "time", time);
            }
        }
    }

    private static String formatted(Duration elapsed) {
        long seconds = elapsed.getSeconds();
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private void endRun() {
        counting = false;
        releaseHunters();
        if (occupancy != null) {
            HandlerList.unregisterAll(occupancy);
            occupancy = null;
        }
        if (ticking != null) {
            closeQuietly(ticking);
            ticking = null;
        }
        session = null;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // A cancel that fails leaves nothing dangerous behind — see ChainService's own note.
        }
    }

    // ------------------------------------------------------------------------ resetting

    /** Resets the configured world by hand — {@code /manhunt reset}, or the admin menu's danger slot. */
    public void resetWorld(SpeedrunSeed seedOverride, java.util.function.Consumer<Boolean> onDone) {
        ManhuntSettings config = settings;
        World world = plugin.getServer().getWorld(config.worldName());
        if (world == null) {
            onDone.accept(false);
            return;
        }
        SpeedrunSeed seed = seedOverride != null ? seedOverride : defaultSeed(config);
        Scheduling.global(plugin, () -> onDone.accept(reset.regenerate(world, seed, teams.everybody())));
    }

    private static SpeedrunSeed defaultSeed(ManhuntSettings config) {
        return config.seedChoice() == ManhuntSettings.SeedChoice.FIXED
                ? SpeedrunSeed.fixed(config.seedValue())
                : SpeedrunSeed.random();
    }

    // ------------------------------------------------------------------------ shutdown

    /** Ends the run that is going, if any, so nobody is left mid-hunt for a clock that will never resume. */
    public void shutdown() {
        if (session != null) {
            session.finish("plugin-disable");
        }
        endRun();
    }

    public String describe() {
        return "running a Manhunt, holding the Hunters for their head start, and resetting the map";
    }
}
