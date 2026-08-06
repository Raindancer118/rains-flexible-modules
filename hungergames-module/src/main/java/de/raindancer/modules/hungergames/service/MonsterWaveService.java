package de.raindancer.modules.hungergames.service;

import de.raindancer.core.world.safety.Spot;
import de.raindancer.core.world.spawn.Spawns;
import de.raindancer.core.world.spawn.Wave;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * Monster waves: a gamemaster picks a spot and, over several waves at a set interval, groups of monsters
 * spawn there.
 *
 * <h2>Reused rather than reinvented</h2>
 * The source engine hand-rolled the ring of spawn points around a centre and its own per-series
 * {@code BukkitRunnable}. Neither is written here: {@link Wave#of} already turns "how many waves, how
 * many per wave, how far to scatter them, how long between waves" into a list of {@link Wave.Pack}s with
 * their own {@code afterTicks}, and {@link Spawns#place} already turns one pack into a ring of
 * {@link Spot}s (via {@code Swarm}) and asks a {@link de.raindancer.core.world.spawn.Spawner} to fill each
 * one. What is left for this class is exactly the part Core cannot know: which packs are due yet, given the
 * round's own elapsed time — {@link #tick} — and the one-time validation a gamemaster's typed monster name
 * needs before it becomes a {@link Wave} at all — {@link #resolveMonster}.
 *
 * <h2>Why this still has its own {@link #tick} instead of a timer per series</h2>
 * A live {@code BukkitTask} per call to {@code /waves start} is one more schedule a restart or a round
 * reset has to remember to cancel, and it is exactly the pattern {@code ReuseTest} forbids in favour of
 * {@code Scheduling}. Here every active series is a value — the {@link Wave} itself, its centre, which
 * packs have already fired — and {@link #tick} (driven by the same round clock as everything else)
 * advances all of them at once. There is nothing to leak, because there is nothing scheduled: a round
 * ending simply stops calling {@link #tick}.
 */
public final class MonsterWaveService implements IHungerGamesService, EventEndpoints.MonsterWaves {

    @FunctionalInterface
    public interface RoundLog {
        void log(String category, String message, Location location);

        default void log(String category, String message) {
            log(category, message, null);
        }
    }

    /** One series still running: the wave itself, its centre, when it started, and which packs already fired. */
    private static final class Active {
        final Wave wave;
        final Spot centre;
        final Duration startedAt;
        final java.util.Set<Integer> fired = new java.util.HashSet<>();

        Active(Wave wave, Spot centre, Duration startedAt) {
            this.wave = wave;
            this.centre = centre;
            this.startedAt = startedAt;
        }
    }

    private final Spawns spawns;
    private final RoundLog roundLog;
    private final Random random;
    private final List<Active> active = new ArrayList<>();

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;

    public MonsterWaveService(Spawns spawns, RoundLog roundLog, Random random) {
        this.spawns = spawns;
        this.roundLog = roundLog;
        this.random = random;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    // ==================== EventEndpoints.MonsterWaves ====================

    @Override
    public int activeSeries() {
        return active.size();
    }

    @Override
    public String defaultMob() {
        return settings.monsterWaveDefaultMob();
    }

    @Override
    public int defaultCount() {
        return settings.monsterWaveCountPerWave();
    }

    @Override
    public int defaultWaves() {
        return settings.monsterWaveWaveCount();
    }

    @Override
    public int defaultInterval() {
        return settings.monsterWaveIntervalSeconds();
    }

    @Override
    public Optional<String> start(Location centre, String mobName, int countPerWave, int totalWaves,
                                   int intervalSeconds, String actor) {
        return start(centre, mobName, countPerWave, totalWaves, intervalSeconds, actor, Duration.ZERO);
    }

    /** As {@link #start}, but with the round's current elapsed time — the first wave fires on the next tick. */
    public Optional<String> start(Location centre, String mobName, int countPerWave, int totalWaves,
                                   int intervalSeconds, String actor, Duration elapsedNow) {
        if (centre.getWorld() == null) {
            return Optional.of("no valid location");
        }
        EntityType type = resolveMonster(mobName);
        if (type == null) {
            return Optional.of("\"" + mobName + "\" is not a spawnable monster");
        }
        if (countPerWave <= 0 || totalWaves <= 0) {
            return Optional.of("count and waves must both be > 0");
        }
        int spread = Math.max(0, settings.monsterWaveSpread());
        long intervalTicks = Math.max(1, intervalSeconds) * 20L;
        Wave wave = Wave.of(List.of(type.name()), totalWaves, countPerWave, spread, intervalTicks);
        Spot spot = new Spot(centre.getWorld().getName(), centre.getBlockX(), centre.getBlockY(),
                centre.getBlockZ());
        active.add(new Active(wave, spot, elapsedNow));

        roundLog.log("WAVES", actor + " started " + totalWaves + " " + type + " wave(s) of "
                + countPerWave + " (every " + intervalSeconds + "s)", centre);
        return Optional.empty();
    }

    /** One tick of every active series: places whatever pack is due now, at the round's current elapsed time. */
    public void tick(Duration elapsed) {
        List<Active> finished = new ArrayList<>();
        for (Active series : active) {
            List<Wave.Pack> packs = series.wave.packs();
            for (int index = 0; index < packs.size(); index++) {
                if (series.fired.contains(index)) {
                    continue;
                }
                Duration dueAt = series.startedAt.plusMillis(packs.get(index).afterTicks() * 50L);
                if (elapsed.compareTo(dueAt) < 0) {
                    continue;
                }
                series.fired.add(index);
                spawns.place(packs.get(index), series.centre, random.nextLong());
            }
            if (series.fired.size() >= packs.size()) {
                finished.add(series);
            }
        }
        active.removeAll(finished);
    }

    @Override
    public int stopAll() {
        int count = active.size();
        active.clear();
        return count;
    }

    // ==================== resolution ====================

    /** Whether {@code name} names a spawnable, living monster — pure, and worth testing on its own. */
    public static EntityType resolveMonster(String name) {
        if (name == null) {
            return null;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        Class<? extends Entity> entityClass = type.getEntityClass();
        if (entityClass == null || !type.isSpawnable() || !LivingEntity.class.isAssignableFrom(entityClass)) {
            return null;
        }
        return type;
    }

    @Override
    public String describe() {
        return "gamemaster-triggered monster waves";
    }
}
