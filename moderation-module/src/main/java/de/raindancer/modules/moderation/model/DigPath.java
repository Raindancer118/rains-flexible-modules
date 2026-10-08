package de.raindancer.modules.moderation.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * The last few places a player dug, for spotting where a tunnel changes direction. Directions are
 * taken over three blocks each, so the two-high rhythm of feet-then-head digging is not a turn.
 */
public final class DigPath {

    private static final int SPAN = 3;
    private final Deque<int[]> recent = new ArrayDeque<>();
    /** Digs still to go before another bend may be reported — one corner is one bend, not three. */
    private int quiet;

    /** A bend: where it happened, and the direction before and after. */
    public record Bend(int[] at, double[] before, double[] after) {
    }

    /** @return a bend, once the dig after it is far enough along to tell */
    public synchronized Optional<Bend> dug(int x, int y, int z) {
        int[] last = recent.peekLast();
        if (last != null && last[0] == x && last[2] == z && Math.abs(last[1] - y) <= 1) {
            // The other half of a two-high tunnel: same column, no new direction.
            return Optional.empty();
        }
        if (last != null && Math.abs(last[0] - x) + Math.abs(last[1] - y) + Math.abs(last[2] - z) > 6) {
            recent.clear();
        }
        recent.addLast(new int[]{x, y, z});
        while (recent.size() > 2 * SPAN + 1) {
            recent.removeFirst();
        }
        if (recent.size() < 2 * SPAN + 1) {
            return Optional.empty();
        }
        if (quiet > 0) {
            quiet--;
            return Optional.empty();
        }
        int[][] points = recent.toArray(new int[0][]);
        int[] start = points[0];
        int[] middle = points[SPAN];
        int[] end = points[2 * SPAN];
        double[] before = minus(middle, start);
        double[] after = minus(end, middle);
        if (angle(before, after) < de.raindancer.modules.moderation.rules.SteeringRule.TURN_DEGREES) {
            return Optional.empty();
        }
        quiet = 2 * SPAN;
        return Optional.of(new Bend(middle, before, after));
    }

    public synchronized void clear() {
        recent.clear();
        quiet = 0;
    }

    private static double angle(double[] a, double[] b) {
        double la = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        double lb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        if (la < 1e-9 || lb < 1e-9) {
            return 0;
        }
        double cos = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (la * lb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    private static double[] minus(int[] a, int[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }
}
