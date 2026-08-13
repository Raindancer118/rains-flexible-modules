package de.raindancer.modules.mannequin.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every player who has hit one mannequin, and everything they hit it with — kept only in memory,
 * the same lifetime as {@link TrainingSession}, and cleared by the same reset button on {@code
 * StatsScreen}. Not persisted to disk: a training tally is meant to describe the current session, not
 * survive a restart.
 */
public record Leaderboard(Map<UUID, PlayerTally> byPlayer) {

    public static final Leaderboard EMPTY = new Leaderboard(Map.of());

    public Leaderboard {
        byPlayer = byPlayer == null ? Map.of() : Map.copyOf(byPlayer);
    }

    public Leaderboard withHit(UUID player, Material weapon, ItemStack sample, double damage) {
        Map<UUID, PlayerTally> next = new LinkedHashMap<>(byPlayer);
        PlayerTally existing = next.get(player);
        next.put(player, (existing == null ? PlayerTally.empty(player) : existing)
                .withHit(weapon, sample, damage));
        return new Leaderboard(next);
    }

    public List<PlayerTally> rankedByTotalDamage() {
        return byPlayer.values().stream()
                .sorted(Comparator.comparingDouble(PlayerTally::totalDamage).reversed())
                .toList();
    }

    public boolean isEmpty() {
        return byPlayer.isEmpty();
    }
}
