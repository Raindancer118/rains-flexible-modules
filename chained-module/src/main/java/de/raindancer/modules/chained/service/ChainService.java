package de.raindancer.modules.chained.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.bossbar.BarPriority;
import de.raindancer.core.ui.bossbar.BarStyle;
import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.SpeedrunEndCondition;
import de.raindancer.modules.speedrun.SpeedrunOccupancyListener;
import de.raindancer.core.RainsCore;
import de.raindancer.core.world.manage.WorldRegenerator;
import de.raindancer.core.world.manage.WorldSeed;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import de.raindancer.modules.speedrun.conditions.AdvancementEndCondition;
import de.raindancer.modules.speedrun.conditions.DeathEndCondition;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.chained.ChainedSettings;
import de.raindancer.modules.chained.model.ChainPair;
import de.raindancer.modules.chained.store.ChainPairStore;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Pairing players, running a speedrun clock for a pair, and resetting the map between attempts.
 *
 * <h2>Why every run goes through here</h2>
 * Because there are two entrances — the command and the admin menu — and an invariant guarded at one
 * is not guarded. Building the Core {@link SpeedrunSession}, wiring the configured end condition and
 * showing the shared boss bar all happen once, here, so the two cannot come to disagree about any of
 * it.
 *
 * <h2>The ticker is a seam, not a direct {@code Scheduling} call</h2>
 * A repeating task needs a live server to actually schedule anything, and a test needs to drive a
 * tick by hand instead — the same reasoning {@code GameTimerService} in the hungergames module gives
 * for the same shape. {@link #viaScheduling} is the real one, onto Core's global-region scheduler;
 * {@link #manual} never schedules anything and hands a test a no-op handle to close.
 */
public final class ChainService implements IChainedService {

    /** Runs {@code task} once a second until told to stop. */
    @FunctionalInterface
    public interface RunTicker {
        AutoCloseable everySecond(Runnable task);
    }

    /** The real ticker: onto Core's own repeating scheduler, once a second. Never {@code Bukkit.getScheduler()}. */
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

    private static final String OWNER = "chained";

    private final Plugin plugin;
    private final ChainPairStore pairs;
    private final BossBars bossBars;
    private final Messages messages;
    private final WorldRegenerator reset;
    private final RunTicker ticker;

    private record Run(SpeedrunSession session, AutoCloseable ticking,
                       SpeedrunOccupancyListener occupancy) {
    }

    private final Map<ChainPair, Run> runs = new ConcurrentHashMap<>();

    private volatile ChainedSettings settings;

    public ChainService(Plugin plugin, ChainPairStore pairs, BossBars bossBars, Messages messages,
                        ChainedSettings settings) {
        this(plugin, pairs, bossBars, messages,
                RainsCore.isAvailable() ? RainsCore.get().worldRegenerator() : new WorldRegenerator(),
                viaScheduling(plugin), settings);
    }

    /** The same, with the world-reset step and the ticker injectable — what the tests use. */
    public ChainService(Plugin plugin, ChainPairStore pairs, BossBars bossBars, Messages messages,
                        WorldRegenerator reset, RunTicker ticker, ChainedSettings settings) {
        this.plugin = plugin;
        this.pairs = pairs;
        this.bossBars = bossBars;
        this.messages = messages;
        this.reset = reset;
        this.ticker = ticker;
        settings(settings);
    }

    @Override
    public void settings(ChainedSettings fresh) {
        this.settings = fresh;
    }

    public ChainPairStore pairs() {
        return pairs;
    }

    // ------------------------------------------------------------------------ pairing

    /** Chains two players together, replacing whatever pair either was already in. */
    public ChainPair pair(UUID first, UUID second, double maxDistance) {
        return pairs.pair(new ChainPair(first, second, maxDistance));
    }

    /** Dissolves this player's pair, ending its run first if one is going. */
    public boolean unpair(UUID player) {
        stop(player);
        return pairs.unpair(player);
    }

    // ------------------------------------------------------------------------ running

    /**
     * Starts a run for this player's pair.
     *
     * @return the session, or empty when there is no pair to run, or one is already going
     */
    public Optional<SpeedrunSession> start(UUID player) {
        ChainPair pair = pairs.pairOf(player).orElse(null);
        if (pair == null || runs.containsKey(pair)) {
            return Optional.empty();
        }
        ChainedSettings config = settings;

        if (config.resetOnStart()) {
            resetWorld(defaultSeed(config), done -> { });
        }

        SpeedrunSession session = new SpeedrunSession(Set.of(pair.a(), pair.b()));
        SpeedrunEndCondition condition = conditionFor(config);
        if (condition != null) {
            session.addEndCondition(condition);
        }
        session.start();

        // The clock must not keep running while both halves of the pair are offline — a chained pair
        // that alt-tabs out for the night must not come back to a run that already "finished" hours
        // later on a technicality. Registered per run, not once for the module, because each run has
        // its own session to watch and its own moment to stop watching one.
        SpeedrunOccupancyListener occupancy = new SpeedrunOccupancyListener(session);
        plugin.getServer().getPluginManager().registerEvents(occupancy, plugin);

        AutoCloseable ticking = ticker.everySecond(() -> tick(pair, session));
        runs.put(pair, new Run(session, ticking, occupancy));
        return Optional.of(session);
    }

    private SpeedrunEndCondition conditionFor(ChainedSettings config) {
        return switch (config.endCondition()) {
            case ADVANCEMENT -> new AdvancementEndCondition(plugin, advancementKey(config));
            case DEATH -> new DeathEndCondition(plugin, config.deathPolicy());
            case MANUAL -> null;
        };
    }

    private static NamespacedKey advancementKey(ChainedSettings config) {
        NamespacedKey key = NamespacedKey.fromString(config.advancementKey());
        return key != null ? key : NamespacedKey.minecraft("end/kill_dragon");
    }

    /** Ends this player's pair's run, if one is going. */
    public boolean stop(UUID player) {
        ChainPair pair = pairs.pairOf(player).orElse(null);
        if (pair == null) {
            return false;
        }
        Run run = runs.get(pair);
        if (run == null) {
            return false;
        }
        run.session().finish("manual");
        endRun(pair);
        return true;
    }

    /** The live session for this player's pair, if a run is going. */
    public Optional<SpeedrunSession> sessionOf(UUID player) {
        ChainPair pair = pairs.pairOf(player).orElse(null);
        if (pair == null) {
            return Optional.empty();
        }
        return sessionOf(pair);
    }

    /** The same, for a caller that has already resolved the pair — skips a second {@code pairOf} lookup. */
    public Optional<SpeedrunSession> sessionOf(ChainPair pair) {
        return Optional.ofNullable(runs.get(pair)).map(Run::session);
    }

    private void tick(ChainPair pair, SpeedrunSession session) {
        if (session.state() == SpeedrunState.FINISHED) {
            endRun(pair);
            return;
        }
        List<UUID> audience = List.of(pair.a(), pair.b());
        bossBars.showShared(OWNER, key(pair), audience, styleFor(session), BarPriority.NORMAL);
    }

    private static BarStyle styleFor(SpeedrunSession session) {
        boolean paused = session.state() == SpeedrunState.PAUSED;
        String text = (paused ? "Chained (paused) — " : "Chained — ") + Times.brief(session.elapsed());
        return BarStyle.of(Component.text(text, NamedTextColor.WHITE))
                .progress(1f)
                .colour(paused ? BossBar.Color.YELLOW : BossBar.Color.PURPLE);
    }

    private void endRun(ChainPair pair) {
        Run run = runs.remove(pair);
        if (run == null) {
            return;
        }
        closeQuietly(run.ticking());
        HandlerList.unregisterAll(run.occupancy());
        bossBars.clearShared(OWNER, key(pair));
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // A cancel that fails leaves nothing dangerous behind — the tick simply outlives the run
            // by at most one more second, and its own FINISHED check turns it back off.
        }
    }

    private static String key(ChainPair pair) {
        return pair.a() + ":" + pair.b();
    }

    // ------------------------------------------------------------------------ resetting

    /**
     * Resets the configured world, without waiting to know whether it came back.
     *
     * @param seedOverride the seed to use, or {@code null} to use the settings' own seed policy
     */
    public void resetWorld(WorldSeed seedOverride) {
        resetWorld(seedOverride, done -> { });
    }

    /**
     * Resets the configured world, through Core's {@link WorldRegenerator}: everybody standing in it is
     * moved out and waited for before it is unloaded, and both seeds go into Core's seed history.
     *
     * @param seedOverride the seed to use, or {@code null} to use the settings' own seed policy
     * @param onDone       told whether the world came back
     */
    public void resetWorld(WorldSeed seedOverride, Consumer<Boolean> onDone) {
        ChainedSettings config = settings;
        World world = plugin.getServer().getWorld(config.worldName());
        if (world == null) {
            onDone.accept(false);
            return;
        }
        WorldSeed seed = seedOverride != null ? seedOverride : defaultSeed(config);
        // Folia: unloading, deleting and recreating a world are global-region operations. resetWorld
        // can be called from a command or a menu click, neither of which runs on that thread, so the
        // hop has to happen here rather than in the caller.
        Scheduling.global(plugin, () -> reset.regenerate(world, seed, onDone));
    }

    private static WorldSeed defaultSeed(ChainedSettings config) {
        return config.seedChoice() == ChainedSettings.SeedChoice.FIXED
                ? WorldSeed.fixed(config.seedValue())
                : WorldSeed.random();
    }

    // ------------------------------------------------------------------------ shutdown

    /** Ends every run that is going, so nobody is left mid-run for a clock that will never resume. */
    public void shutdown() {
        for (ChainPair pair : Set.copyOf(runs.keySet())) {
            Run run = runs.get(pair);
            if (run != null) {
                run.session().finish("plugin-disable");
            }
            endRun(pair);
        }
    }

    @Override
    public String describe() {
        return "pairing players, running a chained speedrun clock, and resetting the map";
    }
}
