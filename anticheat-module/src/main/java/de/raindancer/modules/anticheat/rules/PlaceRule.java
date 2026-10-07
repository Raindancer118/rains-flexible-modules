package de.raindancer.modules.anticheat.rules;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Whether a block could have been placed the way it was.
 *
 * <p>A click lands on one face of a block, and a face can only be seen from its outside: to click the
 * top, the eye must be above it. Exact for full blocks; for odd shapes (stairs, slabs) the face could
 * sit anywhere inside the block, so the test falls back to the block's far side.
 */
public final class PlaceRule implements IAntiCheatRule {

    /**
     * @param eye      where the eye was
     * @param block    the clicked block's own cube (x..x+1, ...)
     * @param shape    its outline, in world coordinates
     * @param fullCube whether the outline is the whole cube
     * @param normalX  the clicked face's normal: -1, 0 or 1 per axis
     */
    public Judgement faceVisible(Vector eye, BoundingBox block, BoundingBox shape, boolean fullCube,
                                 int normalX, int normalY, int normalZ, double tolerance) {
        Judgement x = axis(eye.getX(), block.getMinX(), block.getMaxX(), shape.getMinX(), shape.getMaxX(), fullCube, normalX, tolerance, "east", "west");
        if (x.failed()) {
            return x;
        }
        Judgement y = axis(eye.getY(), block.getMinY(), block.getMaxY(), shape.getMinY(), shape.getMaxY(), fullCube, normalY, tolerance, "top", "bottom");
        if (y.failed()) {
            return y;
        }
        return axis(eye.getZ(), block.getMinZ(), block.getMaxZ(), shape.getMinZ(), shape.getMaxZ(), fullCube, normalZ, tolerance, "south", "north");
    }

    private static Judgement axis(double eye, double blockMin, double blockMax, double shapeMin, double shapeMax,
                                  boolean full, int normal, double tolerance, String positive, String negative) {
        if (normal > 0) {
            double plane = full ? blockMax : shapeMin;
            if (eye < plane - tolerance) {
                return Judgement.fail(plane - eye, String.format("clicked the %s face from %.2f behind it", positive, plane - eye));
            }
        } else if (normal < 0) {
            double plane = full ? blockMin : shapeMax;
            if (eye > plane + tolerance) {
                return Judgement.fail(eye - plane, String.format("clicked the %s face from %.2f behind it", negative, eye - plane));
            }
        }
        return Judgement.PASS;
    }

    /**
     * Placing into the very spot that was clicked only happens with something replaceable there —
     * and air or a liquid cannot be clicked at all: the crosshair passes through them.
     */
    public Judgement againstNothing(boolean clickedIsPlacedSpot, boolean replacedWasAir, boolean replacedWasLiquid) {
        if (!clickedIsPlacedSpot) {
            return Judgement.PASS;
        }
        if (replacedWasAir) {
            return Judgement.fail(1, "placed against air");
        }
        if (replacedWasLiquid) {
            return Judgement.fail(1, "placed against a liquid");
        }
        return Judgement.PASS;
    }

    @Override
    public String describe() {
        return "whether a block was placed against a face that could be seen and clicked";
    }
}
