package de.raindancer.modules.anticheat.rules;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * Reach and hitbox, the way vanilla picks a target: from the eye, along the look direction, to the
 * target's box.
 *
 * <p>Neither the server nor this module knows exactly where both players were when the client
 * decided to hit, so both questions are asked over every candidate: each eye position the attacker
 * recently had, each place the target recently was. Only if every combination fails is it a fail —
 * the benefit of the doubt goes to the player, every time.
 */
public final class CombatRule implements IAntiCheatRule {

    /** A look direction the attacker had, in degrees. */
    public record Rotation(float yaw, float pitch) {
    }

    /** The closest any candidate eye came to any candidate box. */
    /** One arm swing at normal speed — six ticks — with a tick to spare. */
    public static final long SWING_NANOS = 350_000_000L;

    /** Whether an attack this long after the last swing still falls within that swing. */
    public static boolean swingCovers(long sinceSwingNanos) {
        return sinceSwingNanos >= 0 && sinceSwingNanos <= SWING_NANOS;
    }

    public double closest(List<Vector> eyes, List<BoundingBox> boxes) {
        double best = Double.POSITIVE_INFINITY;
        for (Vector eye : eyes) {
            for (BoundingBox box : boxes) {
                best = Math.min(best, Geometry.distance(eye, box));
            }
        }
        return best;
    }

    public Judgement reach(List<Vector> eyes, List<BoundingBox> boxes, double range, double tolerance) {
        if (eyes.isEmpty() || boxes.isEmpty()) {
            return Judgement.PASS;
        }
        double closest = closest(eyes, boxes);
        if (closest > range + tolerance) {
            return Judgement.fail(closest - range, String.format("%.2f blocks away, range %.2f", closest, range));
        }
        return Judgement.PASS;
    }

    /**
     * Whether any of the attacker's recent look directions, from any recent eye position, meets any
     * recent target box grown by {@code grow} on every side.
     */
    public Judgement hitbox(List<Vector> eyes, List<Rotation> rotations, List<BoundingBox> boxes, double grow,
                            double reach) {
        if (eyes.isEmpty() || rotations.isEmpty() || boxes.isEmpty()) {
            return Judgement.PASS;
        }
        double bestAngle = 180;
        for (Vector eye : eyes) {
            for (Rotation rotation : rotations) {
                Vector look = Geometry.direction(rotation.yaw(), rotation.pitch());
                for (BoundingBox box : boxes) {
                    BoundingBox grown = box.clone().expand(grow);
                    if (Geometry.rayHits(eye, look, grown, reach)) {
                        return Judgement.PASS;
                    }
                    bestAngle = Math.min(bestAngle, Geometry.angleTo(eye, look, box.getCenter()));
                }
            }
        }
        return Judgement.fail(bestAngle, String.format("crosshair %.1f° off the target", bestAngle));
    }

    @Override
    public String describe() {
        return "whether a hit came from within reach and with the crosshair on the target";
    }
}
