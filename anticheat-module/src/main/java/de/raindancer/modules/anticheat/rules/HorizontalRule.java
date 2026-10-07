package de.raindancer.modules.anticheat.rules;

/**
 * The fastest a player can move sideways in one tick, from vanilla's own order of operations: the
 * velocity left over after last tick's friction, plus this tick's acceleration (and the sprint-jump
 * boost on the tick of a jump). Anything faster is speed; anything that is only legal without the
 * slowdown of eating, blocking or sneaking is NoSlow.
 */
public final class HorizontalRule implements IAntiCheatRule {

    public static final double TOLERANCE = 0.015;

    /**
     * @param wasOnGround       on ground at the start of this tick (the previous position)
     * @param wasOnGroundBefore on ground at the start of the tick before
     * @param friction          block friction under the previous position
     * @param frictionBefore    block friction under the position before that
     * @param movementSpeed     the movement speed attribute as the server has it now
     * @param sprintCounted     whether {@code movementSpeed} already includes the sprint bonus
     * @param maySprint         whether sprinting is possible at all (not starving, not using an item)
     * @param slowdown          the input multiplier for using an item (0.2) and sneaking (the sneak attribute); 1 for none
     * @param jumped            jumped off the ground this tick
     * @param velocity          horizontal speed of a push the client may apply this tick, or NaN
     * @param push              extra allowance for entities pushing them
     */
    public record Move(double lastHd, double hd, int ticks, boolean wasOnGround, boolean wasOnGroundBefore,
                       double friction, double frictionBefore, double movementSpeed, boolean sprintCounted,
                       boolean maySprint, double slowdown, boolean jumped, double velocity, double push) {
    }

    public enum Outcome { PASS, SPEED, NO_SLOW }

    public record Result(Outcome outcome, double limit, double offset) {

        public boolean failed() {
            return outcome != Outcome.PASS;
        }

        public boolean passed() {
            return outcome == Outcome.PASS;
        }
    }

    public Result judge(Move move) {
        if (Double.isNaN(move.lastHd()) || Double.isNaN(move.hd())) {
            return new Result(Outcome.PASS, 0, 0);
        }
        double slowed = limit(move, move.slowdown(), move.maySprint());
        if (move.hd() <= slowed + TOLERANCE) {
            return new Result(Outcome.PASS, slowed, 0);
        }
        double unslowed = limit(move, 1.0, true);
        if (move.slowdown() < 1.0 && move.hd() <= unslowed + TOLERANCE) {
            return new Result(Outcome.NO_SLOW, slowed, move.hd() - slowed);
        }
        return new Result(Outcome.SPEED, unslowed, move.hd() - unslowed);
    }

    /** The farthest {@code ticks} ticks can go, assuming the best input every tick. */
    public double limit(Move move, double slowdown, boolean maySprint) {
        double speed = move.movementSpeed() * (move.sprintCounted() || !maySprint ? 1 : Physics.SPRINT_MULTIPLIER);
        double ground = Physics.groundAcceleration(speed, move.friction()) * slowdown;
        double air = (maySprint ? Physics.AIR_ACCELERATION_SPRINTING : Physics.AIR_ACCELERATION) * slowdown;
        double accel = move.wasOnGround() ? ground : air;

        double carried = move.lastHd() * (move.wasOnGroundBefore() ? move.frictionBefore() * Physics.AIR_FRICTION : Physics.AIR_FRICTION);
        if (Double.isFinite(move.velocity())) {
            carried = Math.max(carried, move.velocity());
        }
        double v = carried + accel + (move.jumped() && maySprint ? Physics.SPRINT_JUMP_BOOST : 0);
        double total = v;
        double friction = move.wasOnGround() ? move.friction() * Physics.AIR_FRICTION : Physics.AIR_FRICTION;
        for (int tick = 1; tick < Math.max(1, move.ticks()); tick++) {
            v = v * friction + accel;
            total += v;
        }
        return total + Math.max(0, move.push());
    }

    @Override
    public String describe() {
        return "whether a horizontal move fits friction and acceleration";
    }
}
