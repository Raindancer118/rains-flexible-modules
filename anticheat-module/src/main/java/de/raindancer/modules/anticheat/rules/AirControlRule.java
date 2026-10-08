package de.raindancer.modules.anticheat.rules;

import java.util.Locale;

/**
 * Steering where vanilla barely allows it. In the air, each tick keeps 0.91 of the last tick's
 * sideways motion and adds at most the air acceleration (0.026 sprinting) in any direction — so the
 * <em>vector</em> difference is bounded, not just the speed. A cheat that turns or stops in mid-air
 * passes every speed check and fails this one. On ladders the climb is capped at 0.2 a tick.
 */
public final class AirControlRule implements IAntiCheatRule {

    public static final double TOLERANCE = 0.01;
    public static final double CLIMB = 0.2;

    /** The previous and current sideways move, both while airborne the whole time. */
    public Judgement judge(double lastDx, double lastDz, double dx, double dz, int ticks) {
        int n = Math.max(1, ticks);
        double carriedX = lastDx;
        double carriedZ = lastDz;
        double predictedX = 0;
        double predictedZ = 0;
        for (int tick = 0; tick < n; tick++) {
            carriedX *= Physics.AIR_FRICTION;
            carriedZ *= Physics.AIR_FRICTION;
            predictedX += carriedX;
            predictedZ += carriedZ;
        }
        // Each tick's push carries forward into the later ticks, so n ticks allow up to this much in total.
        double allowance = 0;
        double weight = 0;
        for (int tick = 0; tick < n; tick++) {
            weight = weight * Physics.AIR_FRICTION + 1;
            allowance += weight;
        }
        allowance *= Physics.AIR_ACCELERATION_SPRINTING;
        double offset = Math.hypot(dx - predictedX, dz - predictedZ) - allowance;
        if (offset > TOLERANCE) {
            return Judgement.fail(offset, String.format(Locale.ROOT,
                    "changed course in the air by %.3f, at most %.3f", offset + allowance, allowance));
        }
        return Judgement.PASS;
    }

    public Judgement climb(double dy) {
        if (dy > CLIMB + 0.03) {
            return Judgement.fail(dy - CLIMB, String.format(Locale.ROOT, "climbing at %.3f, ladders allow %.2f", dy, CLIMB));
        }
        return Judgement.PASS;
    }

    @Override
    public String describe() {
        return "whether steering in the air and climbing fit what vanilla allows";
    }
}
