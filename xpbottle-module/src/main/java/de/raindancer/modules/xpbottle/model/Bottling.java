package de.raindancer.modules.xpbottle.model;

/**
 * What came of trying to move experience into a bottle.
 *
 * <p>A record rather than an {@code int}, because "nothing moved" has three different reasons and a
 * player is told a different sentence for each: the bottle was already full, they had nothing to put
 * in it, or it worked. A bare zero collapses those into a click that appears to do nothing, which is
 * the one outcome that gets clicked again.
 *
 * @param moved  points that actually went in
 * @param bottle the bottle as it is afterwards
 */
public record Bottling(int moved, Bottle bottle, Reason reason) {

    public enum Reason {
        /** Points moved. */
        FILLED,
        /** The bottle is at capacity; nothing moved. */
        ALREADY_FULL,
        /** There was no experience to take; nothing moved. */
        NOTHING_TO_TAKE
    }

    public static Bottling of(int moved, Bottle after) {
        return new Bottling(moved, after, Reason.FILLED);
    }

    public static Bottling alreadyFull(Bottle bottle) {
        return new Bottling(0, bottle, Reason.ALREADY_FULL);
    }

    public static Bottling nothingToTake(Bottle bottle) {
        return new Bottling(0, bottle, Reason.NOTHING_TO_TAKE);
    }

    public boolean happened() {
        return moved > 0;
    }
}
