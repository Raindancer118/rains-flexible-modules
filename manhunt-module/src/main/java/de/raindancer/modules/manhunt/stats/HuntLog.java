package de.raindancer.modules.manhunt.stats;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Everything one hunt does, written down as it happens — the timeline, and each player's running
 * numbers — and turned into a {@link HuntRecord} at the end.
 *
 * <p>Bukkit-free, so the arithmetic of a hunt's record is tested without a server. Written from
 * events on many region threads and from the once-a-second sampler, hence every method is
 * synchronized: a hunt produces a handful of entries a minute, never enough to contend.
 */
public final class HuntLog {

    private final LongSupplier clock;
    private final long startedAt;
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<UUID, Boolean> runner = new LinkedHashMap<>();
    private final Map<UUID, Integer> catches = new LinkedHashMap<>();
    private final Map<UUID, Integer> deaths = new LinkedHashMap<>();
    private final Map<UUID, Integer> portals = new LinkedHashMap<>();
    private final Map<UUID, Double> distance = new LinkedHashMap<>();
    private final Map<UUID, Long> caughtAt = new LinkedHashMap<>();
    private final Map<Milestone, Long> milestones = new EnumMap<>(Milestone.class);
    private final List<TimelineEvent> events = new ArrayList<>();

    public HuntLog(LongSupplier clockMillis, Map<UUID, String> runners, Map<UUID, String> hunters) {
        this.clock = clockMillis;
        this.startedAt = clockMillis.getAsLong();
        runners.forEach((id, name) -> join(id, name, true));
        hunters.forEach((id, name) -> join(id, name, false));
        add(TimelineEvent.Kind.STARTED, null, null, null, null, String.valueOf(runners.size()));
    }

    private void join(UUID id, String name, boolean asRunner) {
        names.put(id, name);
        runner.put(id, asRunner);
    }

    /** Milliseconds since the hunt began. */
    public synchronized long elapsed() {
        return clock.getAsLong() - startedAt;
    }

    public synchronized void caught(UUID who, String name, UUID by, String byName) {
        bump(deaths, who);
        if (by != null && !by.equals(who)) {
            bump(catches, by);
        }
        caughtAt.putIfAbsent(who, elapsed());
        add(TimelineEvent.Kind.CAUGHT, who, name, by, byName, null);
    }

    public synchronized void lifeLost(UUID who, String name, UUID by, String byName, int livesLeft) {
        bump(deaths, who);
        add(TimelineEvent.Kind.LIFE_LOST, who, name, by, byName, String.valueOf(livesLeft));
    }

    public synchronized void caughtAway(UUID who, String name) {
        caughtAt.putIfAbsent(who, elapsed());
        add(TimelineEvent.Kind.CAUGHT_AWAY, who, name, null, null, null);
    }

    public synchronized void hunterDied(UUID who, String name, UUID by, String byName) {
        bump(deaths, who);
        add(TimelineEvent.Kind.HUNTER_DIED, who, name, by, byName, null);
    }

    public synchronized void left(UUID who, String name) {
        runner.remove(who);
        add(TimelineEvent.Kind.LEFT, who, name, null, null, null);
    }

    public synchronized void joined(UUID who, String name, boolean asRunner) {
        join(who, name, asRunner);
        add(TimelineEvent.Kind.JOINED, who, name, null, null, asRunner ? "runner" : "hunter");
    }

    public synchronized void sideChanged(UUID who, String name, boolean nowRunner) {
        join(who, name, nowRunner);
        add(TimelineEvent.Kind.SIDE_CHANGED, who, name, null, null, nowRunner ? "runner" : "hunter");
    }

    /** @return whether this was the first time — the moment to tell everybody */
    public synchronized boolean milestone(Milestone milestone, UUID who, String name) {
        if (milestones.containsKey(milestone)) {
            return false;
        }
        milestones.put(milestone, elapsed());
        add(TimelineEvent.Kind.MILESTONE, who, name, null, null, milestone.id());
        return true;
    }

    public synchronized Optional<Long> milestoneAt(Milestone milestone) {
        return Optional.ofNullable(milestones.get(milestone));
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

    public synchronized HuntRecord finish(int number, String reason, HuntRecord.Winner winner) {
        long duration = elapsed();
        add(TimelineEvent.Kind.FINISHED, null, null, null, null, reason);
        List<PlayerResult> results = new ArrayList<>();
        runner.forEach((id, isRunner) -> {
            boolean won = winner == (isRunner ? HuntRecord.Winner.RUNNERS : HuntRecord.Winner.HUNTERS);
            Long caught = caughtAt.get(id);
            results.add(new PlayerResult(id, names.get(id), isRunner, won, isRunner && caught != null,
                    catches.getOrDefault(id, 0), deaths.getOrDefault(id, 0),
                    isRunner ? (caught == null ? duration : caught) : 0,
                    distance.getOrDefault(id, 0.0), portals.getOrDefault(id, 0)));
        });
        return new HuntRecord(number, clock.getAsLong() - duration, duration, reason, winner, results, events);
    }

    private void add(TimelineEvent.Kind kind, UUID who, String whoName, UUID other, String otherName,
                     String detail) {
        events.add(new TimelineEvent(clock.getAsLong() - startedAt, kind, who, whoName, other, otherName, detail));
    }

    private static void bump(Map<UUID, Integer> counter, UUID who) {
        if (who != null) {
            counter.merge(who, 1, Integer::sum);
        }
    }
}
