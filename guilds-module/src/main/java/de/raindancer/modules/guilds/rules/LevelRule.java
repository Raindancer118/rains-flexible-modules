package de.raindancer.modules.guilds.rules;

/**
 * How guild experience becomes a level, and what each level unlocks.
 *
 * <p>The curve is {@code base × (level − 1)^growth} experience in total to reach a level: level 1 is free, and
 * with growth above 1 every level costs more than the one before, so a guild of thirty does not reach the top in
 * a weekend. Perks grow linearly with level and stop at their caps.
 */
public final class LevelRule implements IGuildsRule {

    private final long base;
    private final double growth;
    private final int topLevel;
    private final int membersAtFirst;
    private final int membersPerLevel;
    private final int mostMembers;
    private final int alliesAtFirst;
    private final int levelsPerAlly;
    private final int homeFromLevel;

    public LevelRule(long base, double growth, int topLevel, int membersAtFirst, int membersPerLevel,
                     int mostMembers, int alliesAtFirst, int levelsPerAlly, int homeFromLevel) {
        this.base = Math.max(1, base);
        this.growth = Math.max(1.0, growth);
        this.topLevel = Math.max(1, topLevel);
        this.membersAtFirst = Math.max(1, membersAtFirst);
        this.membersPerLevel = Math.max(0, membersPerLevel);
        this.mostMembers = mostMembers <= 0 ? Integer.MAX_VALUE : Math.max(this.membersAtFirst, mostMembers);
        this.alliesAtFirst = Math.max(0, alliesAtFirst);
        this.levelsPerAlly = Math.max(1, levelsPerAlly);
        this.homeFromLevel = Math.max(1, homeFromLevel);
    }

    @Override
    public String describe() {
        return "a guild reaches level n at " + base + "×(n−1)^" + growth + " experience, up to level " + topLevel;
    }

    /** Experience needed, in total, to be at {@code level}. */
    public long toReach(int level) {
        if (level <= 1) {
            return 0;
        }
        double needed = base * Math.pow(Math.min(level, topLevel) - 1, growth);
        return needed >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(needed);
    }

    public int levelOf(long xp) {
        int level = 1;
        while (level < topLevel && xp >= toReach(level + 1)) {
            level++;
        }
        return level;
    }

    public boolean isTop(int level) {
        return level >= topLevel;
    }

    public int topLevel() {
        return topLevel;
    }

    /** How far through the current level, 0..1; 1 at the top. */
    public double progress(long xp) {
        int level = levelOf(xp);
        if (isTop(level)) {
            return 1.0;
        }
        long from = toReach(level);
        long to = toReach(level + 1);
        return Math.max(0, Math.min(1, (double) (xp - from) / Math.max(1, to - from)));
    }

    public int memberCap(int level) {
        long cap = (long) membersAtFirst + (long) membersPerLevel * (Math.max(1, level) - 1);
        return (int) Math.min(mostMembers, cap);
    }

    public int allySlots(int level) {
        return alliesAtFirst + (Math.max(1, level) - 1) / levelsPerAlly;
    }

    public boolean hasHome(int level) {
        return level >= homeFromLevel;
    }

    public int homeFromLevel() {
        return homeFromLevel;
    }
}
