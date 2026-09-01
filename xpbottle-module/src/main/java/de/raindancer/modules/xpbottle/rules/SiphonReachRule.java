package de.raindancer.modules.xpbottle.rules;

/**
 * Whether a loose experience orb is close enough for a siphon bottle to take it.
 *
 * <h2>Why squared distances</h2>
 * The caller has the two positions and Bukkit hands it {@code distanceSquared} without a square
 * root; taking one per orb per tick, for every player holding a siphon down, is arithmetic nobody
 * needs. The rule therefore takes the squared distance and squares the reach itself, which is also
 * why it is a rule rather than an inline {@code <} — the version that compares a squared distance
 * against an unsquared radius has been written by everybody at least once, and it silently makes
 * the reach the square root of what the settings say.
 */
public final class SiphonReachRule implements IXpBottleRule {

    /**
     * @param distanceSquared how far the orb is, squared, in blocks
     * @param reach           how far this tier reaches, in blocks; zero or less reaches nothing
     */
    public boolean reaches(double distanceSquared, double reach) {
        if (reach <= 0) {
            return false;
        }
        if (Double.isNaN(distanceSquared) || distanceSquared < 0) {
            return false;
        }
        return distanceSquared <= reach * reach;
    }

    @Override
    public String describe() {
        return "whether a loose experience orb is within a siphon bottle's reach";
    }
}
