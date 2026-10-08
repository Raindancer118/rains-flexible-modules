package de.raindancer.modules.anticheat.rules;

/**
 * Rotations a mouse cannot produce.
 *
 * <p>The game turns the camera by whole mouse counts times a step fixed by the sensitivity setting:
 * {@code (s·0.6 + 0.2)³ · 8 · 0.15} degrees. Even at the lowest sensitivity that step is about 0.0096°,
 * so a run of pitch changes sharing no common step at least that large was not made by a mouse. The
 * spyglass and smooth camera break the rule legitimately, which is why this only ever alerts.
 */
public final class AimRule implements IAntiCheatRule {

    public static final double SMALLEST_STEP = Math.pow(0.2, 3) * 8 * 0.15;
    private static final double EPSILON = 1e-4;

    /**
     * How far one reported pitch change can be off an exact multiple of the step: the client keeps pitch
     * as a float and adds every frame's turn to it, each addition rounding by up to half an ulp of 90°.
     */
    static final double DELTA_ERROR = 6e-5;

    /**
     * The largest step every change is a whole multiple of, or 0 if there is none worth the name.
     *
     * <p>Euclid does not survive float noise: the error of a large turn divided by a small one passes
     * for a step of a ten-thousandth of a degree. So candidates come from the smallest change — it is
     * some small count of steps — and each is fitted to all changes and kept only if every one of them
     * lands within rounding of a multiple. Random aim fits a 0.01° grid by chance with odds of about
     * 1 in 125 per change, which over a window of changes is never.
     */
    public double commonStep(double[] deltas) {
        double smallest = Double.MAX_VALUE;
        int moving = 0;
        for (double delta : deltas) {
            double value = Math.abs(delta);
            if (value >= EPSILON) {
                smallest = Math.min(smallest, value);
                moving++;
            }
        }
        if (moving == 0) {
            return 0;
        }
        double[] sorted = new double[moving];
        int at = 0;
        for (double delta : deltas) {
            if (Math.abs(delta) >= EPSILON) {
                sorted[at++] = Math.abs(delta);
            }
        }
        java.util.Arrays.sort(sorted);
        int most = (int) Math.floor(smallest / (SMALLEST_STEP * 0.9));
        for (int counts = 1; counts <= most; counts++) {
            double step = fit(sorted, smallest / counts);
            if (step > 0) {
                return step;
            }
        }
        return 0;
    }

    /**
     * Fits a candidate step to the changes, smallest first, refining it by least squares as the counts
     * grow — a big flick is a thousand steps, where a candidate off by a hair would miscount it. 0 if
     * some change is not a multiple.
     */
    private static double fit(double[] sorted, double candidate) {
        double step = candidate;
        double weighted = 0;
        double squares = 0;
        double mostCounts = 1;
        for (double value : sorted) {
            double counts = Math.rint(value / step);
            if (counts < 1 || Math.abs(value - counts * step) > DELTA_ERROR * (1 + counts / mostCounts)) {
                return 0;
            }
            weighted += counts * value;
            squares += counts * counts;
            step = weighted / squares;
            mostCounts = Math.max(mostCounts, counts);
        }
        for (double value : sorted) {
            if (Math.abs(value - Math.rint(value / step) * step) > DELTA_ERROR * 1.5) {
                return 0;
            }
        }
        return step;
    }

    /** @param pitchDeltas recent pitch changes in degrees, from turns of a decent size */
    public Judgement noSensitivityStep(double[] pitchDeltas) {
        int moving = 0;
        for (double delta : pitchDeltas) {
            if (Math.abs(delta) >= EPSILON) {
                moving++;
            }
        }
        if (moving < 15) {
            return Judgement.PASS;
        }
        double step = commonStep(pitchDeltas);
        if (step < SMALLEST_STEP * 0.9) {
            return Judgement.fail(SMALLEST_STEP - step, String.format("pitch moves share no mouse step (%.5f°, least %.5f°)", step, SMALLEST_STEP));
        }
        return Judgement.PASS;
    }

    /** A bot that keeps yaw within one turn jumps a whole circle at the seam; a mouse never does. */
    public Judgement wrapped(double previousRawDelta, double rawDelta) {
        if (Math.abs(rawDelta) > 320 && Math.abs(previousRawDelta) < 30) {
            return Judgement.fail(Math.abs(rawDelta), String.format("yaw jumped %.1f° and back to the same heading", rawDelta));
        }
        return Judgement.PASS;
    }

    @Override
    public String describe() {
        return "whether rotations look like a mouse made them";
    }
}
