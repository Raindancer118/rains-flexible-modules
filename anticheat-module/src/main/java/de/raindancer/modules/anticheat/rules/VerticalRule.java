package de.raindancer.modules.anticheat.rules;

/**
 * Whether one vertical move fits gravity.
 *
 * <p>Vanilla moves first and applies gravity and drag afterwards, so the move of a tick is the
 * velocity left over from the last one, minus gravity, times drag. A move may come out lower than
 * that (a floor or ceiling clips it) but never higher, unless the player jumped, stepped up, bounced
 * or was pushed. Free fall may never be faster than that either, unless something pushed down.
 */
public final class VerticalRule implements IAntiCheatRule {

    public static final double TOLERANCE = 0.02;
    /** The most a flagged rise carries on past the limit; an unexpected honest jump on Lilly was 0.087. */
    public static final double MOST_CARRIED_EXCESS = 0.12;

    /**
     * @param lastDy       the previous move's height change, or NaN when unknown
     * @param ticks        how many client ticks this move covers
     * @param wasOnGround  standing on something before this move, by the server's collision check
     * @param onGroundNow  standing on something after it
     * @param ceiling      something solid just above the head, before or after
     * @param velocityY    an upward or downward push the server sent and the client may apply now, or NaN
     * @param bounce       upward speed a slime block or bed may give back, 0 for none
     */
    public record Move(double lastDy, double dy, int ticks, boolean wasOnGround, boolean onGroundNow,
                       boolean ceiling, double gravity, double jumpVelocity, double stepHeight,
                       double velocityY, double bounce) {
    }

    /** The lowest and highest a move may go. */
    public record Bounds(double lowest, double highest) {
    }

    /**
     * What the next tick builds on: a move within the tolerance, held within what was allowed — built on the
     * move as it was, a client rising just under the tolerance every tick piled the excess up under air drag.
     */
    public static double carry(double dy, Bounds bounds) {
        if (bounds == null || Double.isNaN(dy) || Double.isNaN(bounds.lowest()) || Double.isNaN(bounds.highest())) {
            return dy;
        }
        // Past the tolerance it was flagged already: the arc goes on from where it really is — but a rise only
        // so far past the limit, or one huge jump bought a free arc.
        if (dy > bounds.highest() + TOLERANCE) {
            return Math.min(dy, bounds.highest() + MOST_CARRIED_EXCESS);
        }
        if (dy < bounds.lowest() - TOLERANCE * 1.5) {
            return dy;
        }
        return Math.max(bounds.lowest(), Math.min(bounds.highest(), dy));
    }

    public Bounds bounds(Move move) {
        int ticks = Math.max(1, move.ticks());
        // Standing, the ground has stopped them: a step up or a landing leaves no speed to carry on with.
        double speed = move.wasOnGround() ? 0 : move.lastDy();
        double first = Physics.nextVertical(speed, move.gravity());
        double expected = Physics.travelled(first, ticks, move.gravity());

        double highest = expected;
        double lowest = expected;
        if (move.wasOnGround()) {
            highest = Math.max(highest, Physics.travelled(move.jumpVelocity(), ticks, move.gravity()));
            if (ticks == 1 && move.onGroundNow()) {
                highest = Math.max(highest, move.stepHeight());
            }
        }
        if (Double.isFinite(move.velocityY())) {
            double pushed = Physics.travelled(move.velocityY(), ticks, move.gravity());
            highest = Math.max(highest, pushed);
            lowest = Math.min(lowest, pushed);
        }
        if (move.bounce() > 0) {
            highest = Math.max(highest, Physics.travelled(move.bounce(), ticks, move.gravity()));
        }
        return new Bounds(lowest, highest);
    }

    public Judgement judge(Move move) {
        if (Double.isNaN(move.lastDy()) || Double.isNaN(move.dy())) {
            return Judgement.PASS;
        }
        Bounds bounds = bounds(move);
        double highest = bounds.highest();
        double lowest = bounds.lowest();
        double dy = move.dy();
        boolean landedOrStanding = move.onGroundNow() && dy <= TOLERANCE;
        if (!landedOrStanding && dy > highest + TOLERANCE) {
            String reason = Math.abs(dy) < 0.005 ? "hovering"
                    : move.wasOnGround() && move.onGroundNow() ? "stepped too high"
                    : move.wasOnGround() ? "jumped too high" : "rising against gravity";
            return Judgement.fail(dy - highest, reason + String.format(" (dy %.4f, at most %.4f)", dy, highest));
        }
        // A floor or a ceiling only ever clips a move towards zero, so nothing makes it fall faster.
        if (!move.ceiling() && dy < lowest - TOLERANCE * 1.5) {
            return Judgement.fail(lowest - dy, String.format("falling faster than gravity (dy %.4f, at least %.4f)", dy, lowest));
        }
        return Judgement.PASS;
    }

    @Override
    public String describe() {
        return "whether a vertical move fits gravity, a jump, a step or a push";
    }
}
