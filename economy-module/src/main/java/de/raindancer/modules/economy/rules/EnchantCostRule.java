package de.raindancer.modules.economy.rules;

/**
 * What an enchanted book costs to make, the same way for every enchantment; only its usefulness
 * ({@link EnchantWorthRule}) tells one from another.
 *
 * <p>A level-I enchantment costs a full enchanting-table roll: 3 lapis and the experience from level 27 to 30
 * (306 points). Every level above is made on an anvil from two of the level below, which costs both of them,
 * the book one of them was on, and the anvil's levels (as many as the new level, counted from nothing).
 * An enchantment's ladder is that ordinary ladder times its usefulness per level — and never below the
 * anvil route, so a higher book is never cheaper than combining two lower ones.
 */
public final class EnchantCostRule implements IEconomyRule {

    public static final int TABLE_LAPIS = 3;
    public static final int TABLE_FROM = 27;
    public static final int TABLE_TO = 30;

    /** Beyond this a price means nothing; kept far from overflow. */
    private static final long MOST = Long.MAX_VALUE / 8;

    /** Total experience points to reach {@code level} from nothing. */
    public static long points(int level) {
        long l = Math.max(0, level);
        if (l <= 16) {
            return l * l + 6 * l;
        }
        if (l <= 31) {
            return Math.round(2.5 * l * l - 40.5 * l + 360);
        }
        return Math.round(4.5 * l * l - 162.5 * l + 2220);
    }

    /** One level-I enchantment; zero when experience has no price, so nothing can be worked out. */
    public double roll(double pointValue, long lapis) {
        if (!(pointValue > 0)) {
            return 0;
        }
        return TABLE_LAPIS * (double) Math.max(0, lapis) + (points(TABLE_TO) - points(TABLE_FROM)) * pointValue;
    }

    /** The anvil's levels for combining two books into {@code level}, in money. */
    public long anvil(int level, double pointValue) {
        return pointValue > 0 ? Math.min(MOST, Math.round(points(level) * pointValue)) : 0;
    }

    /**
     * The enchantment's part of each level's value, I first — the book comes on top.
     *
     * @param factor usefulness per level, ordinary 1; a curse passes its size, the sign is the caller's
     */
    public long[] ladder(int maxLevel, double factor, double roll, long book, double pointValue) {
        int max = Math.max(1, maxLevel);
        double scale = Math.max(0, factor);
        long[] ladder = new long[max];
        double ordinary = roll;
        ladder[0] = cap(Math.round(scale * roll));
        for (int level = 2; level <= max; level++) {
            long anvil = anvil(level, pointValue);
            ordinary = 2 * ordinary + book + anvil;
            long combined = cap(2 * ladder[level - 2] + Math.max(0, book) + anvil);
            ladder[level - 1] = Math.max(cap(Math.round(scale * ordinary)), combined);
        }
        return ladder;
    }

    /**
     * The most the shop may pay for each level, I first, against today's buy prices: a cent under the cheapest
     * way to get it from the shop — buying it, or buying lower books and combining them. {@code Long.MAX_VALUE}
     * where no lower book is for sale.
     *
     * @param anvil the anvil's price for each level, I first (level I's is unused)
     */
    public long[] sellCaps(long[] buy, boolean[] buyable, long[] anvil) {
        int max = buy.length;
        long[] caps = new long[max];
        long[] cheapest = new long[max];
        for (int i = 0; i < max; i++) {
            long route = Long.MAX_VALUE;
            if (i > 0 && cheapest[i - 1] != Long.MAX_VALUE) {
                route = cap(2 * cheapest[i - 1] + Math.max(0, anvil[i]));
            }
            caps[i] = route == Long.MAX_VALUE ? Long.MAX_VALUE : route - 1;
            cheapest[i] = buyable[i] ? Math.min(buy[i], route) : route;
        }
        return caps;
    }

    private static long cap(long value) {
        return Math.max(0, Math.min(MOST, value));
    }

    @Override
    public String describe() {
        return "what an enchanted book costs to make: lapis, experience and the anvil, times its usefulness";
    }
}
