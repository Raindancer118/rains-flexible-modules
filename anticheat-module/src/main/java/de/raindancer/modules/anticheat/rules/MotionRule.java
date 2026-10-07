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
