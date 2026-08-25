package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.modules.wallsroads.model.ElevationMode;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.RoadSegment;
import de.raindancer.modules.wallsroads.model.SegmentKind;

import java.util.ArrayList;
import java.util.List;

/**
 * What a road has to do at every step along it: lie on the ground, be carried over a gap, be bored
 * through a hill, or run under the sea in a glass tube.
 *
 * <h2>Why this is one pass over the whole route rather than a decision per column</h2>
 * "Is this a bridge?" cannot be answered by looking at one column. A road over a six-block stream is
 * a bridge; the same road over eight hundred blocks of ocean is a tunnel, and the only difference
 * between them is <em>how long the crossing is</em> — which the column itself does not know. The
 * same for a hill: whether to climb it or bore through it depends on how far up it goes and how long
 * it stays there.
 *
 * <p>Everything here is arithmetic over {@link TerrainReader.Reading}s, so a route can be profiled
 * against a made-up landscape in a test and asserted block by block.
 */
public final class RouteProfiler {

    /**
     * The thresholds that decide what a road does.
     *
     * @param maxGrade          the most blocks a road may rise or fall per block travelled
     * @param smoothing         how many columns either side the ground height is averaged over
     * @param bridgeMinGap      how far above the ground the road must be before it is a bridge
     * @param tunnelMinCover    how far below the surface before it is a tunnel
     * @param maxBridgeSpan     the widest gap a road will span rather than descend into
     * @param seaTunnelMinLength how long a water crossing must be before it goes under instead of over
     * @param seaTunnelMinDepth how deep that water must be for a tunnel to be worth it
     * @param seaTunnelBelowSurface how much water there has to be over the tunnel's roof before going
     *                              under is worth it at all — it is not worth tunnelling ten blocks
     */
    public record Rules(int maxGrade, int smoothing, int bridgeMinGap, int tunnelMinCover,
                        int maxBridgeSpan, int seaTunnelMinLength, int seaTunnelMinDepth,
                        int seaTunnelBelowSurface) {

        public static final Rules DEFAULTS = new Rules(1, 3, 2, 2, 64, 24, 6, 10);

        /** The old six-value form, for callers that predate the depth. */
        public Rules(int maxGrade, int smoothing, int bridgeMinGap, int tunnelMinCover,
                     int maxBridgeSpan, int seaTunnelMinLength, int seaTunnelMinDepth) {
            this(maxGrade, smoothing, bridgeMinGap, tunnelMinCover, maxBridgeSpan,
                    seaTunnelMinLength, seaTunnelMinDepth, 8);
        }

        public Rules {
            maxGrade = Math.max(1, maxGrade);
            smoothing = Math.max(0, smoothing);
            bridgeMinGap = Math.max(1, bridgeMinGap);
            tunnelMinCover = Math.max(1, tunnelMinCover);
            maxBridgeSpan = Math.max(4, maxBridgeSpan);
            seaTunnelMinLength = Math.max(4, seaTunnelMinLength);
            seaTunnelMinDepth = Math.max(2, seaTunnelMinDepth);
            seaTunnelBelowSurface = Math.max(2, Math.min(64, seaTunnelBelowSurface));
        }
    }

    private final TerrainReader terrain = new TerrainReader();

    public List<RoadSegment> profile(RoadPath road, Ground ground, Rules rules) {
        List<Column> centreline = road.path().orderedColumns();
        if (centreline.isEmpty()) {
            return List.of();
        }

        List<TerrainReader.Reading> readings = new ArrayList<>(centreline.size());
        for (Column column : centreline) {
            readings.add(terrain.read(ground, road.world(), column));
        }

        int[] height = road.elevationMode() == ElevationMode.FIXED_Y
                ? flat(readings.size(), road.fixedY())
                : follow(readings, rules);

        List<RoadSegment> plan = new ArrayList<>(centreline.size());
        for (int i = 0; i < centreline.size(); i++) {
            TerrainReader.Reading reading = readings.get(i);
            plan.add(new RoadSegment(centreline.get(i), classify(height[i], reading, rules),
                    height[i], reading));
        }
        return plan;
    }

    private static int[] flat(int length, int y) {
        int[] height = new int[length];
        java.util.Arrays.fill(height, y);
        return height;
    }

    /**
     * The height the road wants at every step, from the ground it is crossing.
     *
     * <p>Four passes, in this order and no other: <em>what the ground says</em>, then smoothed so a
     * single boulder does not put a step in the road, then dips filled so a gap is spanned rather
     * than descended into, then the grade capped so nothing climbs faster than a road can. Filling
     * before capping matters — capped first, a road would begin diving into a ravine and the fill
     * would then span the dive rather than the ravine.
     */
    private int[] follow(List<TerrainReader.Reading> readings, Rules rules) {
        boolean[] submerged = new boolean[readings.size()];
        int[] wanted = waterAwareBase(readings, rules, submerged);
        int[] smoothed = smooth(wanted, rules.smoothing());
        int[] spanned = fillDips(smoothed, rules, submerged);
        return capGrade(spanned, rules.maxGrade());
    }

    /**
     * The starting height per column, with water already decided.
     *
     * <p>A crossing that is long <em>and</em> deep aims for the sea bed — that is the ocean tunnel.
     * Everything else aims for just above the water, which is a bridge. Deciding this per run rather
     * than per column is what stops a road diving under a brook.
     */
    private int[] waterAwareBase(List<TerrainReader.Reading> readings, Rules rules, boolean[] submerged) {
        int[] base = new int[readings.size()];
        for (int i = 0; i < readings.size(); i++) {
            base[i] = readings.get(i).groundY();
        }
        for (int[] run : waterRuns(readings)) {
            int from = run[0];
            int to = run[1];
            int length = to - from + 1;
            int deepest = 0;
            int highestSurface = Integer.MIN_VALUE;
            for (int i = from; i <= to; i++) {
                deepest = Math.max(deepest, readings.get(i).waterDepth());
                highestSurface = Math.max(highestSurface, readings.get(i).waterSurfaceY());
            }
            // Deep enough is not "there is water": the tunnel's own roof has to end up well under the
            // surface, or the result is a glass box sitting in a lagoon — which is exactly what a
            // six-deep crossing produced. Its roof stands `roofClearance` above the bed, so that much
            // water plus a margin is what the crossing actually has to have.
            int roofClearance = rules.seaTunnelBelowSurface();
            boolean deepEnough = deepest >= Math.max(rules.seaTunnelMinDepth(), roofClearance);
            boolean goesUnder = length >= rules.seaTunnelMinLength() && deepEnough;
            for (int i = from; i <= to; i++) {
                if (!goesUnder) {
                    // Over: one block clear of the water, level across the whole crossing — a bridge
                    // that follows a choppy surface is a staircase.
                    base[i] = highestSurface + 1;
                    continue;
                }
                // Under: on the sea bed, which is where a road under the sea belongs — the glass is
                // there so the water is the view, and a tube hung in mid-water has the bed as a
                // distant floor rather than as the ground the road is laid on.
                base[i] = readings.get(i).groundY();
                submerged[i] = true;
            }
        }
        return base;
    }

    /** The start and end index of every unbroken stretch of water along the route. */
    private List<int[]> waterRuns(List<TerrainReader.Reading> readings) {
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < readings.size(); i++) {
            boolean wet = readings.get(i).isUnderWater();
            if (wet && start < 0) {
                start = i;
            } else if (!wet && start >= 0) {
                runs.add(new int[] {start, i - 1});
                start = -1;
            }
        }
        if (start >= 0) {
            runs.add(new int[] {start, readings.size() - 1});
        }
        return runs;
    }

    private static int[] smooth(int[] values, int radius) {
        if (radius <= 0) {
            return values.clone();
        }
        int[] smoothed = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            int from = Math.max(0, i - radius);
            int to = Math.min(values.length - 1, i + radius);
            long sum = 0;
            for (int j = from; j <= to; j++) {
                sum += values[j];
            }
            smoothed[i] = (int) Math.round(sum / (double) (to - from + 1));
        }
        return smoothed;
    }

    /**
     * Holds the road level across a gap instead of following the ground down into it.
     *
     * <p>Every pair of shoulders no further apart than {@code maxBridgeSpan} is considered, and
     * wherever the straight line between them runs above the ground, that line is the road. A gap
     * wider than the span is left alone deliberately: a road does not fly across a whole valley, it
     * goes down into it, and a bridge with no visible end is not a bridge.
     */
    private static int[] fillDips(int[] values, Rules rules, boolean[] deliberate) {
        int[] filled = values.clone();
        for (int from = 0; from < values.length; from++) {
            int to = Math.min(values.length - 1, from + rules.maxBridgeSpan());
            for (int end = to; end > from + 1; end--) {
                int span = end - from;
                for (int i = from + 1; i < end; i++) {
                    // A sea tunnel is not a dip to be spanned — it is where the road is meant to be.
                    // Without this the span-filling lifts the tunnel back towards the surface until it
                    // is a glass box floating in a lagoon, which is what the first one looked like.
                    if (deliberate[i]) {
                        continue;
                    }
                    double t = (i - from) / (double) span;
                    int line = (int) Math.round(values[from] + (values[end] - values[from]) * t);
                    if (line > filled[i]) {
                        filled[i] = line;
                    }
                }
            }
        }
        return filled;
    }

    /**
     * Caps how fast the road may rise, from both ends.
     *
     * <p>Only ever lowers a height, never raises one — raising would undo the dip filling above and
     * put the road back in the air over ground it was meant to be lying on. Both directions because
     * a single forward pass leaves the far side of every hill as a cliff.
     */
    private static int[] capGrade(int[] values, int maxGrade) {
        int[] capped = values.clone();
        for (int i = 1; i < capped.length; i++) {
            capped[i] = Math.min(capped[i], capped[i - 1] + maxGrade);
        }
        for (int i = capped.length - 2; i >= 0; i--) {
            capped[i] = Math.min(capped[i], capped[i + 1] + maxGrade);
        }
        return capped;
    }

    private static SegmentKind classify(int surfaceY, TerrainReader.Reading reading, Rules rules) {
        if (reading.isUnderWater() && surfaceY <= reading.waterSurfaceY()) {
            return SegmentKind.GLASS_TUNNEL;
        }
        if (surfaceY >= reading.groundY() + rules.bridgeMinGap()) {
            return SegmentKind.BRIDGE;
        }
        if (surfaceY <= reading.groundY() - rules.tunnelMinCover()) {
            return SegmentKind.TUNNEL;
        }
        return SegmentKind.GROUND;
    }
}
