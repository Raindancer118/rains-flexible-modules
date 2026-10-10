package de.raindancer.modules.anticheat.model;

/**
 * How often a player's moves may be taken on their word because a block around them changed. A miner
 * needs it for a move or three per block; somebody placing and breaking blocks around themselves
 * every tick to keep every move unjudgeable does not get it for long.
 */
public final class Excuses {

    public static final int IN_A_ROW = 12;

    private final Buffer used = new Buffer(IN_A_ROW, 0.5);

    /** @param wanted whether this move would need the excuse @return whether it gets it */
    public boolean grant(boolean wanted) {
        if (!wanted) {
            used.pass();
            return false;
        }
        return !used.fail(1);
    }
}
