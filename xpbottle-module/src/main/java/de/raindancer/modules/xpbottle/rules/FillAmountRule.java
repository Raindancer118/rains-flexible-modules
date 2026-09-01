package de.raindancer.modules.xpbottle.rules;

import de.raindancer.modules.xpbottle.model.Bottle;
import de.raindancer.modules.xpbottle.model.Bottling;

/**
 * How many experience points move into a bottle — the one sum this module cannot get wrong.
 *
 * <h2>Why both ways in share it</h2>
 * Draining a player's own bar and swallowing an orb off the ground are the same question asked about
 * a different source: <em>how much is there, how much room is left, and is there a ceiling on this
 * particular go</em>. Written twice they would drift, and the way they drift is always the same one —
 * one of the two forgets the room and hands out points the bottle never held.
 *
 * <h2>The ceiling</h2>
 * {@link #movedAtMost} exists for the siphon, which is deliberately not instantaneous: it pulls a
 * budget of points per tick so that filling a bottle is something a player watches happen rather than
 * a flash. Filling from your own bar has no ceiling, and says so by going through {@link #moved}.
 */
public final class FillAmountRule implements IXpBottleRule {

    /**
     * How much moves when there is no per-go ceiling.
     *
     * @param available points there are to take
     * @param bottle    the bottle as it stands
     */
    public Bottling moved(int available, Bottle bottle) {
        return movedAtMost(available, bottle, Integer.MAX_VALUE);
    }

    /**
     * How much moves, never more than {@code ceiling} in one go.
     *
     * <p>The order of the two refusals matters and is tested: a full bottle held against a player
     * with no experience is reported as full, because that is the one the player can do something
     * about.
     *
     * @param available points there are to take; negative is read as none
     * @param bottle    the bottle as it stands
     * @param ceiling   the most that may move this go; anything below one moves nothing
     */
    public Bottling movedAtMost(int available, Bottle bottle, int ceiling) {
        Bottle safe = bottle == null ? Bottle.empty(0) : bottle;
        if (safe.room() <= 0) {
            return Bottling.alreadyFull(safe);
        }
        int there = Math.max(0, available);
        if (there == 0) {
            return Bottling.nothingToTake(safe);
        }
        int moving = Math.min(there, safe.room());
        if (ceiling < moving) {
            moving = Math.max(0, ceiling);
        }
        if (moving == 0) {
            return Bottling.nothingToTake(safe);
        }
        return Bottling.of(moving, safe.plus(moving));
    }

    @Override
    public String describe() {
        return "how many experience points move into a bottle, given what is there, what room is "
                + "left and whatever ceiling this go has";
    }
}
