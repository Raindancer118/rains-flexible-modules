package de.raindancer.modules.speedrun.manhunt.stats;

import de.raindancer.modules.speedrun.SpeedrunHistory;
import de.raindancer.modules.speedrun.SpeedrunTimeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Each player's running numbers in one hunt — side, catches, deaths, distance, portals, when they
 * were caught — and what happened to whom, written onto the run's own {@link SpeedrunTimeline} as
 * it happens. At the end it turns into the run's per-player results, which the one speedrun history
 * keeps with the run.
 *
 * <p>Bukkit-free, so the arithmetic of a hunt's record is tested without a server. Written from
 * events on many region threads and from the once-a-second sampler, hence every method is
 * synchronized: a hunt produces a handful of entries a minute, never enough to contend.
 */
public final class HuntLog {

    private final LongSupplier clock;
    private final long startedAt;
    private final SpeedrunTimeline timeline;
    private final Supplier<Duration> runClock;
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<UUID, Boolean> runner = new LinkedHashMap<>();
    private final Map<UUID, Integer> catches = new LinkedHashMap<>();
    private final Map<UUID, Integer> deaths = new LinkedHashMap<>();
    private final Map<UUID, Integer> portals = new LinkedHashMap<>();
    private final Map<UUID, Double> distance = new LinkedHashMap<>();
    private final Map<UUID, Long> caughtAt = new LinkedHashMap<>();

    /**
     * @param timeline the run's own timeline, what happens to whom is written onto; {@code null}
     *                 to keep only the numbers
     * @param runClock the run's clock, so a timeline entry reads the time the racers saw
     */
    public HuntLog(LongSupplier clockMillis, Map<UUID, String> runners, Map<UUID, String> hunters,
                   SpeedrunTimeline timeline, Supplier<Duration> runClock) {
        this.clock = clockMillis;
        this.startedAt = clockMillis.getAsLong();
        this.timeline = timeline;
        this.runClock = runClock;
        runners.forEach((id, name) -> join(id, name, true));
        hunters.forEach((id, name) -> join(id, name, false));
    }

    /** Numbers only, nothing on a timeline. */
    public HuntLog(LongSupplier clockMillis, Map<UUID, String> runners, Map<UUID, String> hunters) {
        this(clockMillis, runners, hunters, null, null);
    }

    private void join(UUID id, String name, boolean asRunner) {
        names.put(id, name);
        runner.put(id, asRunner);
    }

    public synchronized long elapsed() {
        return clock.getAsLong() - startedAt;
    }

    public synchronized void caught(UUID who, String name, UUID by, String byName) {
        bump(deaths, who);
        if (by != null && !by.equals(who)) {
            bump(catches, by);
        }
        caughtAt.putIfAbsent(who, elapsed());
        note(SpeedrunTimeline.Kind.CAUGHT, who, "", by);
    }

    public synchronized void lifeLost(UUID who, String name, UUID by, String byName, int livesLeft) {
        bump(deaths, who);
        note(SpeedrunTimeline.Kind.LIFE_LOST, who, String.valueOf(livesLeft), by);
    }

    public synchronized void caughtAway(UUID who, String name) {
        caughtAt.putIfAbsent(who, elapsed());
        note(SpeedrunTimeline.Kind.CAUGHT_AWAY, who, "", null);
    }

    public synchronized void hunterDied(UUID who, String name, UUID by, String byName) {
        bump(deaths, who);
        note(SpeedrunTimeline.Kind.HUNTER_DIED, who, "", by);
    }

    /** Off the hunt: not scored at all. The session's own roster change is what the timeline says. */
    public synchronized void left(UUID who, String name) {
        runner.remove(who);
    }

    /** A latecomer; the session's own roster change is what the timeline says. */
    public synchronized void joined(UUID who, String name, boolean asRunner) {
        join(who, name, asRunner);
    }

    public synchronized void sideChanged(UUID who, String name, boolean nowRunner) {
        join(who, name, nowRunner);
        note(SpeedrunTimeline.Kind.SIDE_CHANGED, who, nowRunner ? "running" : "hunting", null);
    }

    public synchronized void portal(UUID who) {
        bump(portals, who);
    }

    public synchronized void travelled(UUID who, double blocks) {
        if (blocks > 0) {
            distance.merge(who, blocks, Double::sum);
        }
    }

    public synchronized double distanceOf(UUID who) {
        return distance.getOrDefault(who, 0.0);
    }

    public synchronized boolean isRunner(UUID who) {
        return runner.getOrDefault(who, false);
    }

    /**
     * Everybody's result, as the hunt ends.
     *
     * @param winner {@link SpeedrunHistory#RUNNERS}, {@link SpeedrunHistory#HUNTERS}, or empty
     */
    public synchronized List<PlayerResult> results(String winner) {
        long duration = elapsed();
        List<PlayerResult> results = new ArrayList<>();
        runner.forEach((id, isRunner) -> {
            boolean won = (isRunner ? SpeedrunHistory.RUNNERS : SpeedrunHistory.HUNTERS).equals(winner);
            Long caught = caughtAt.get(id);
            results.add(new PlayerResult(id, names.get(id), isRunner, won, isRunner && caught != null,
                    catches.getOrDefault(id, 0), deaths.getOrDefault(id, 0),
                    isRunner ? (caught == null ? duration : caught) : 0,
                    distance.getOrDefault(id, 0.0), portals.getOrDefault(id, 0)));
        });
        return List.copyOf(results);
    }

    private void note(SpeedrunTimeline.Kind kind, UUID who, String detail, UUID other) {
        if (timeline != null) {
            timeline.record(kind, runClock == null ? Duration.ofMillis(elapsed()) : runClock.get(), who, detail,
                    other);
        }
    }

    private static void bump(Map<UUID, Integer> counter, UUID who) {
        if (who != null) {
            counter.merge(who, 1, Integer::sum);
        }
    }
}
