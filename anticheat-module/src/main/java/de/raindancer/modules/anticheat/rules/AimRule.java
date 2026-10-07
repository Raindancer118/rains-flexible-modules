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

    /** The greatest common step of a run of angle changes, tolerant of float rounding. */
    public double commonStep(double[] deltas) {
        double gcd = 0;
        for (double delta : deltas) {
            double value = Math.abs(delta);
            if (value < EPSILON) {
                continue;
            }
            gcd = gcd == 0 ? value : gcd(gcd, value);
        }
        return gcd;
    }

    private static double gcd(double a, double b) {
        if (a < b) {
            double swap = a;
            a = b;
            b = swap;
        }
        while (b > EPSILON) {
            double rest = a % b;
            a = b;
            b = rest;
        }
        return a;
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
