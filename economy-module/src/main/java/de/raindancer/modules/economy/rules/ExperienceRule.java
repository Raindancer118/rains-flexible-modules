package de.raindancer.modules.economy.rules;

/**
 * Experience levels as points, by Minecraft's own formula, so levels can be priced fairly: a high level
 * holds many more points than a low one, and points are what a player gathers and spends.
 */
public final class ExperienceRule implements IEconomyRule {

    /** No buying past this — far beyond anything an anvil asks, and well inside an int of points. */
    public static final int MOST_LEVEL = 10_000;

    /** Points from the start of {@code level} to the next. */
    public int toNext(int level) {
        if (level >= 31) {
            return 9 * level - 158;
        }
        if (level >= 16) {
            return 5 * level - 38;
        }
        return 2 * level + 7;
    }

    /** All points it takes to reach {@code level} from nothing. */
    public int pointsAtLevel(int level) {
        long l = Math.max(0, level);
        double total = l <= 16 ? l * l + 6 * l
                : l <= 31 ? 2.5 * l * l - 40.5 * l + 360
                : 4.5 * l * l - 162.5 * l + 2220;
        return (int) Math.round(total);
    }

    public int levelOf(int points) {
        int level = 0;
        while (level < MOST_LEVEL && pointsAtLevel(level + 1) <= points) {
            level++;
        }
        return level;
    }

    /** What {@code levels} more cost in points, keeping the bar at the same share of the way. */
    public int pointsToGain(int points, int levels) {
        int level = levelOf(points);
        int target = Math.min(MOST_LEVEL, level + Math.max(0, Math.min(levels, MOST_LEVEL)));
        if (target <= level) {
            return 0;
        }
        double share = (points - pointsAtLevel(level)) / (double) toNext(level);
        int after = pointsAtLevel(target) + (int) Math.round(share * toNext(target));
        return Math.max(0, after - points);
    }

    /** What {@code levels} fewer give up in points, keeping the bar's share; everything when that is more than there is. */
    public int pointsToLose(int points, int levels) {
        int level = levelOf(points);
        if (levels >= level) {
            return Math.max(0, points);
        }
        double share = (points - pointsAtLevel(level)) / (double) toNext(level);
        int target = level - Math.max(0, levels);
        int after = pointsAtLevel(target) + (int) Math.floor(share * toNext(target));
        return Math.max(0, points - after);
    }

    @Override
    public String describe() {
        return "experience levels as points, to price them";
    }
}
