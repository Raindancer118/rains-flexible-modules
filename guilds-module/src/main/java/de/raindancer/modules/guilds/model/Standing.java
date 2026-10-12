package de.raindancer.modules.guilds.model;

import java.util.EnumMap;
import java.util.Map;

/**
 * A guild's honours and what it has bought with them.
 *
 * @param levelPoints points from levels ever gained — a prestige resets the level, never these
 * @param prestige    how often the guild went past the top and started again
 * @param crowns      weeks it earned the most experience of all guilds
 * @param perks       the rank of each perk it bought; missing means 0
 * @param respecAt    when it last took its points back, epoch millis; 0 for never
 */
public record Standing(int levelPoints, int prestige, int crowns, Map<Perk, Integer> perks, long respecAt) {

    public static final Standing NONE = new Standing(0, 0, 0, Map.of(), 0);

    public Standing {
        levelPoints = Math.max(0, levelPoints);
        prestige = Math.max(0, prestige);
        crowns = Math.max(0, crowns);
        Map<Perk, Integer> copy = new EnumMap<>(Perk.class);
        if (perks != null) {
            perks.forEach((perk, rank) -> {
                if (rank != null && rank > 0) {
                    copy.put(perk, Math.min(rank, perk.maxRank()));
                }
            });
        }
        perks = Map.copyOf(copy);
    }

    public int rank(Perk perk) {
        return perks.getOrDefault(perk, 0);
    }

    public int spent() {
        return perks.values().stream().mapToInt(Integer::intValue).sum();
    }

    public Standing withLevelPoints(int points) {
        return new Standing(points, prestige, crowns, perks, respecAt);
    }

    public Standing withPrestige(int times) {
        return new Standing(levelPoints, times, crowns, perks, respecAt);
    }

    public Standing withCrowns(int won) {
        return new Standing(levelPoints, prestige, won, perks, respecAt);
    }

    public Standing withPerk(Perk perk, int rank) {
        Map<Perk, Integer> next = new EnumMap<>(Perk.class);
        next.putAll(perks);
        next.put(perk, rank);
        return new Standing(levelPoints, prestige, crowns, next, respecAt);
    }

    public Standing respecced(long now) {
        return new Standing(levelPoints, prestige, crowns, Map.of(), now);
    }
}
