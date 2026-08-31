package de.raindancer.modules.manhunt.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.bossbar.BarPriority;
import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.time.Times;
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
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
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
 * start, shows a shared clock, and resets the map between attempts.
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
        STARTED, ALREADY_RUNNING, NO_RUNNERS, NO_HUNTERS, WORLD_MISSING
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

    private static final String OWNER = "manhunt";
    private static final String BAR_ID = "run";

    private final Plugin plugin;
    private final ManhuntTeams teams;
    private final BossBars bossBars;
    private final Messages messages;
    private final SpeedrunReset reset;
    private final RunTicker ticker;

    private volatile ManhuntSettings settings;

    private Consumer<Set<UUID>> onStart = roster -> { };
    private BiConsumer<Set<UUID>, SpeedrunOutcome> onFinished = (roster, outcome) -> { };

    private SpeedrunSession session;
    private SpeedrunOccupancyListener occupancy;
    private HunterHoldListener hold;
    private AutoCloseable ticking;

    public ManhuntService(Plugin plugin, ManhuntTeams teams, BossBars bossBars, Messages messages,
                          ManhuntSettings settings) {
        this(plugin, teams, bossBars, messages, new SpeedrunReset(), viaScheduling(plugin), settings);
    }

    /** The same, with the world-reset step and the ticker injectable — what the tests use. */
    ManhuntService(Plugin plugin, ManhuntTeams teams, BossBars bossBars, Messages messages,
                  SpeedrunReset reset, RunTicker ticker, ManhuntSettings settings) {
        this.plugin = plugin;
        this.teams = teams;
        this.bossBars = bossBars;
        this.messages = messages;
        this.reset = reset;
        this.ticker = ticker;
        settings(settings);
    }

    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
    }

    public ManhuntSettings config() {
        return settings;
    }

    public ManhuntTeams teams() {
        return teams;
    }

    /** Whether the roster may currently change sides — {@link ManhuntTeams}' own "fact about the moment". */
    public boolean isRunning() {
        return session != null && session.state() != SpeedrunState.FINISHED;
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
        return StartOutcome.STARTED;
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
            case ALL_RUNNERS_DEAD -> new AllRunnersDeadEndCondition(plugin, runners);
            case TIMEOUT -> new TimeoutEndCondition(plugin, Duration.ofMinutes(config.hunterTimeoutMinutesClamped()));
        };
        return List.of(runnerSide, hunterSide);
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

    private void tick() {
        if (session == null) {
            return;
        }
        if (session.state() == SpeedrunState.FINISHED) {
            endRun();
            return;
        }
        bossBars.showShared(OWNER, BAR_ID, List.copyOf(teams.everybody()), styleFor(session), BarPriority.NORMAL);
    }

    private static BarStyle styleFor(SpeedrunSession session) {
        boolean paused = session.state() == SpeedrunState.PAUSED;
        String text = (paused ? "Manhunt (paused) — " : "Manhunt — ") + Times.brief(session.elapsed());
        return BarStyle.of(Component.text(text, NamedTextColor.WHITE))
                .progress(1f)
                .colour(paused ? BossBar.Color.YELLOW : BossBar.Color.RED);
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
        releaseHunters();
        if (occupancy != null) {
            HandlerList.unregisterAll(occupancy);
            occupancy = null;
        }
        if (ticking != null) {
            closeQuietly(ticking);
            ticking = null;
        }
        bossBars.clearShared(OWNER, BAR_ID);
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
