package de.raindancer.modules.ctf.rules;

/**
 * Experience and levels. Level {@code n} needs {@code 50·n·(n−1)} experience: 100 for level 2, 300 for 3, 600 for 4 —
 * each level a hundred more than the one before.
 */
public final class LevelRule implements ICtfRule {

    public static final int PER_CAPTURE = 30;
    public static final int PER_RETURN = 15;
    public static final int PER_KILL = 5;
    public static final int PER_CARRIER_KILL = 10;
    public static final int FOR_WINNING = 50;
    public static final int FOR_PLAYING = 20;

    public long xpFor(int level) {
        long n = Math.max(1, level);
        return 50L * n * (n - 1);
    }

    public int level(long xp) {
        if (xp <= 0) {
            return 1;
        }
        int level = (int) Math.floor((1 + Math.sqrt(1 + 8.0 * xp / 100.0)) / 2);
        while (xpFor(level + 1) <= xp) {
            level++;
        }
        while (level > 1 && xpFor(level) > xp) {
            level--;
        }
        return level;
    }

    /** How far into their current level somebody is, 0 to just under 1. */
    public float progress(long xp) {
        int level = level(xp);
        long from = xpFor(level);
        long to = xpFor(level + 1);
        return (float) (xp - from) / (to - from);
    }

    public int earned(int captures, int returns, int kills, int carrierKills, boolean won) {
        return captures * PER_CAPTURE + returns * PER_RETURN + kills * PER_KILL + carrierKills * PER_CARRIER_KILL
                + (won ? FOR_WINNING : 0) + FOR_PLAYING;
    }
}
