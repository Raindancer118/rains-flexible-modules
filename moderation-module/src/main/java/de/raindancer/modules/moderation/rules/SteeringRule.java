package de.raindancer.modules.moderation.rules;

/**
 * Whether a tunnel's change of direction aimed at ore nobody honest could know about.
 *
 * <p>A turn is judged against the nearest <em>hidden</em> valuable ore around the turn — whether or
 * not the tunnel ever reaches it. Judging against the ore the tunnel eventually found would be
 * circular: every tunnel bends toward whatever it ends up at.
 *
 * <p>Nor is "half the turns toward it" the honest expectation: honest miners turn toward rock they
 * have not dug yet, and that is where hidden ore still is. So every hidden ore around a turn is paired
 * with a control — hidden plain rock at the same distance and height (see {@code HiddenOreRule}) —
 * and a turn counts by which of the two it favoured overall. To an honest miner they are
 * interchangeable, so a turn favours the ore exactly as often as the controls.
 */
public final class SteeringRule implements IModerationRule {

    /** Directions must differ by this much to be a turn at all. */
    public static final double TURN_DEGREES = 35;
    /** And the angle to the ore must change by this much to count as toward or away. */
    public static final double DECISIVE_DEGREES = 15;

    public enum Verdict { NOT_A_TURN, NEUTRAL, TOWARD, AWAY }

    /**
     * @param before direction of digging before the turn (any length)
     * @param after  direction after it
     * @param toOre  from the turn to the hidden ore
     */
    public Verdict judge(double[] before, double[] after, double[] toOre) {
        if (length(before) < 1e-9 || length(after) < 1e-9 || length(toOre) < 1e-9) {
            return Verdict.NOT_A_TURN;
        }
        if (angle(before, after) < TURN_DEGREES) {
            return Verdict.NOT_A_TURN;
        }
        double was = angle(before, toOre);
        double now = angle(after, toOre);
        if (now < was - DECISIVE_DEGREES) {
            return Verdict.TOWARD;
        }
        if (now > was + DECISIVE_DEGREES) {
            return Verdict.AWAY;
        }
        return Verdict.NEUTRAL;
    }

    public enum Pair { ORE_ONLY, DECOY_ONLY, NO_DIFFERENCE }

    /**
     * One turn: summed over every hidden ore around it, did it head for the ore more than for the
     * ore's paired control? {@code ores} and {@code controls} are matched index for index.
     */
    public Pair judgeCensus(double[] before, double[] after, int[] at, java.util.List<int[]> ores, java.util.List<int[]> controls) {
        if (ores.isEmpty() || angle(before, after) < TURN_DEGREES) {
            return Pair.NO_DIFFERENCE;
        }
        int balance = 0;
        for (int i = 0; i < ores.size(); i++) {
            balance += sign(judge(before, after, to(at, ores.get(i)))) - sign(judge(before, after, to(at, controls.get(i))));
        }
        return balance > 0 ? Pair.ORE_ONLY : balance < 0 ? Pair.DECOY_ONLY : Pair.NO_DIFFERENCE;
    }

    private static int sign(Verdict verdict) {
        return verdict == Verdict.TOWARD ? 1 : verdict == Verdict.AWAY ? -1 : 0;
    }

    private static double[] to(int[] from, int[] target) {
        return new double[]{target[0] - from[0], target[1] - from[1], target[2] - from[2]};
    }

    static double angle(double[] a, double[] b) {
        double cos = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (length(a) * length(b));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    private static double length(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    @Override
    public String describe() {
        return "whether a tunnel turned toward ore that could not be seen";
    }
}
