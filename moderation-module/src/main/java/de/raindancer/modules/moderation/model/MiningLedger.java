package de.raindancer.modules.moderation.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * What one player's digging revealed, over weeks rather than minutes. Every count fades with a
 * half-life, so a player is judged on how they mine lately, and an old suspicion wears off.
 *
 * <ul>
 *   <li><b>revealed</b> per band: enclosed blocks their digging exposed for the first time</li>
 *   <li><b>veins</b> per band and ore: how many of those were a new ore vein</li>
 *   <li><b>bait</b>: honeypot ores their digging reached, against how many chance alone would reach</li>
 *   <li><b>turns</b>: changes of direction that favoured hidden ore over a hidden decoy, or the other way</li>
 * </ul>
 */
public final class MiningLedger {

    public static final int TRAIL = 2000;
    public static final int FINDS = 500;
    public static final int ROCK = -1;
    public static final int BAIT = -2;

    /** A vein (ore ordinal) or a bait ({@link #BAIT}) their digging reached. */
    public record Find(String world, int x, int y, int z, int kind) {
    }

    private final Map<String, Double> revealed = new HashMap<>();
    private final Map<String, EnumMap<OreKind, Double>> veins = new HashMap<>();
    private double baitReached;
    private double baitExpected;
    private double turnsToward;
    private double turnsAway;
    private double totalRevealed;
    private long updatedMillis;
    /** Dig positions, oldest first: {x, y, z, kind ({@link #ROCK}, ore ordinal, {@link #BAIT})} — for the replay. */
    private final Deque<int[]> trail = new ArrayDeque<>();
    private String trailWorld = "";
    /**
     * Kept apart from the trail: the trail is rock-heavy and starts over in every world, and the
     * moderator must still be able to see the finds behind the counts.
     */
    private final Deque<Find> finds = new ArrayDeque<>();

    public MiningLedger(long nowMillis) {
        this.updatedMillis = nowMillis;
    }

    /** Lets every count fade for the time since the last change. */
    public synchronized void decay(long nowMillis, double halfLifeMillis) {
        if (halfLifeMillis <= 0 || nowMillis <= updatedMillis) {
            updatedMillis = Math.max(updatedMillis, nowMillis);
            return;
        }
        double factor = Math.pow(0.5, (nowMillis - updatedMillis) / halfLifeMillis);
        revealed.replaceAll((band, value) -> value * factor);
        veins.values().forEach(kinds -> kinds.replaceAll((kind, value) -> value * factor));
        baitReached *= factor;
        baitExpected *= factor;
        turnsToward *= factor;
        turnsAway *= factor;
        totalRevealed *= factor;
        updatedMillis = nowMillis;
    }

    public synchronized void revealed(RockBand band, int count) {
        revealed.merge(band.key(), (double) count, Double::sum);
        totalRevealed += count;
    }

    public synchronized void vein(RockBand band, OreKind kind) {
        veins.computeIfAbsent(band.key(), ignored -> new EnumMap<>(OreKind.class)).merge(kind, 1.0, Double::sum);
    }

    public synchronized void bait(double expected, int reached) {
        baitExpected += Math.max(0, expected);
        baitReached += Math.max(0, reached);
    }

    public synchronized void turn(boolean toward) {
        if (toward) {
            turnsToward++;
        } else {
            turnsAway++;
        }
    }

    public synchronized void dug(String world, int x, int y, int z, int kind) {
        if (!world.equals(trailWorld)) {
            trail.clear();
            trailWorld = world;
        }
        trail.addLast(new int[]{x, y, z, kind});
        while (trail.size() > TRAIL) {
            trail.removeFirst();
        }
        if (kind != ROCK) {
            finds.addLast(new Find(world, x, y, z, kind));
            while (finds.size() > FINDS) {
                finds.removeFirst();
            }
        }
    }

    public synchronized Map<String, Double> revealedByBand() {
        return Map.copyOf(revealed);
    }

    public synchronized double veins(String bandKey, OreKind kind) {
        EnumMap<OreKind, Double> kinds = veins.get(bandKey);
        return kinds == null ? 0 : kinds.getOrDefault(kind, 0.0);
    }

    public synchronized Map<String, Map<OreKind, Double>> veinsByBand() {
        Map<String, Map<OreKind, Double>> copy = new HashMap<>();
        veins.forEach((band, kinds) -> copy.put(band, Map.copyOf(kinds)));
        return copy;
    }

    public synchronized double baitReached() {
        return baitReached;
    }

    public synchronized double baitExpected() {
        return baitExpected;
    }

    public synchronized double turnsToward() {
        return turnsToward;
    }

    public synchronized double turnsAway() {
        return turnsAway;
    }

    public synchronized double totalRevealed() {
        return totalRevealed;
    }

    public synchronized long updatedMillis() {
        return updatedMillis;
    }

    public synchronized String trailWorld() {
        return trailWorld;
    }

    public synchronized java.util.List<int[]> trail() {
        return java.util.List.copyOf(trail);
    }

    public synchronized java.util.List<Find> finds() {
        return java.util.List.copyOf(finds);
    }

    /** For loading from disk. */
    public synchronized void restore(Map<String, Double> revealedByBand, Map<String, Map<OreKind, Double>> veinsByBand,
                                     double baitReached, double baitExpected, double toward, double away, long updated,
                                     String world, java.util.List<int[]> savedTrail, java.util.List<Find> savedFinds) {
        revealed.clear();
        revealed.putAll(revealedByBand);
        totalRevealed = revealedByBand.values().stream().mapToDouble(Double::doubleValue).sum();
        veins.clear();
        veinsByBand.forEach((band, kinds) -> {
            EnumMap<OreKind, Double> map = new EnumMap<>(OreKind.class);
            map.putAll(kinds);
            veins.put(band, map);
        });
        this.baitReached = baitReached;
        this.baitExpected = baitExpected;
        this.turnsToward = toward;
        this.turnsAway = away;
        this.updatedMillis = updated;
        this.trailWorld = world == null ? "" : world;
        trail.clear();
        savedTrail.forEach(trail::addLast);
        finds.clear();
        if (savedFinds != null) {
            savedFinds.forEach(finds::addLast);
        } else {
            for (int[] step : savedTrail) {
                if (step[3] != ROCK) {
                    finds.addLast(new Find(trailWorld, step[0], step[1], step[2], step[3]));
                }
            }
        }
        while (finds.size() > FINDS) {
            finds.removeFirst();
        }
    }
}
