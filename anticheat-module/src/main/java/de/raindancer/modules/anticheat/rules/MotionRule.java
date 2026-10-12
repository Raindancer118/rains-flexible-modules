package de.raindancer.modules.anticheat.rules;

/** Knockback and critical hits: two places where the client decides how it moves, and may lie. */
public final class MotionRule implements IAntiCheatRule {

    /**
     * Whether the knockback the server sent was taken. Only the upward part is judged on its own: it
     * is applied whole in the first tick (nothing but a ceiling can stop it), while sideways
     * knockback mixes with the player's own input.
     *
     * @param sentY       the upward speed sent
     * @param highestRise the largest single-tick rise seen while it could have been applied
     */
    public Judgement knockbackTaken(double sentY, double highestRise) {
        if (sentY < 0.2) {
            return Judgement.PASS;
        }
        if (highestRise < sentY * 0.8 - 0.01) {
            double taken = Math.max(0, highestRise / sentY);
            return Judgement.fail(1 - taken, String.format("took %.0f%% of the knockback (rose %.3f of %.3f)", taken * 100, highestRise, sentY));
        }
        return Judgement.PASS;
    }

    /** The most one tick of a player's own input can do against a push: a sprinting step on the ground. */
    static final double INPUT_AGAINST = 0.13;
    /** And what is too little a push to tell apart from walking at all. */
    static final double SMALLEST_JUDGED = 0.05;

    /**
     * Whether the sideways part of knockback was taken. Vanilla halves the speed they had and adds the
     * push, then their input works against it for at most a tick before the push has carried them.
     *
     * @param sent        the sideways push sent
     * @param bestAlong   the furthest they moved along its direction in one tick while it could apply
     * @param speedBefore how fast they moved the tick before it
     */
    public Judgement knockbackTakenSideways(double sent, double bestAlong, double speedBefore) {
        double least = sent - speedBefore * 0.5 - INPUT_AGAINST;
        if (least < SMALLEST_JUDGED) {
            return Judgement.PASS;
        }
        if (bestAlong < least - 0.02) {
            return Judgement.fail(least - bestAlong, String.format(java.util.Locale.ROOT,
                    "took no sideways knockback (moved %.3f along a push of %.3f, at least %.3f)", bestAlong, sent, least));
        }
        return Judgement.PASS;
    }

    /**
     * A critical hit needs a real fall. Hopping a few hundredths of a block is how criticals cheats
     * fake one: the player never left the ground by more than a whisker and never jumped.
     *
     * @param heightAboveGround how far the feet are above whatever is under them
     * @param recentRise        the largest upward move in the last dozen ticks
     * @param fallSinceTop      how far they came down since the highest point of that
     */
    public Judgement critical(boolean critical, double heightAboveGround, double recentRise, double fallSinceTop) {
        if (!critical) {
            return Judgement.PASS;
        }
        if (heightAboveGround < 0.15 && recentRise < 0.3 && fallSinceTop < 0.15) {
            return Judgement.fail(0.15 - fallSinceTop, String.format("critical from %.3f above the ground without a jump", heightAboveGround));
        }
        return Judgement.PASS;
    }

    @Override
    public String describe() {
        return "whether knockback was taken and critical hits came from a real fall";
    }
}
