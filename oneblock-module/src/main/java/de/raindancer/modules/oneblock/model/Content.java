package de.raindancer.modules.oneblock.model;

import org.bukkit.Material;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the islands are made of: the blocks each dimension's block turns into by itself, the upgrades for sale, what
 * shards are earned at and what unlocking a dimension costs. Read from {@code content.yml}; the shipped file is the
 * default, the owner's wins.
 *
 * @param pools        weighted, per dimension: what a break turns the block into when no upgrade's roll hits
 * @param unlockPrices shards per dimension; the overworld is never for sale
 * @param shardEvery   one award per this many breaks of any block
 * @param shardAmount  shards per award
 * @param teamSize     the most players on one island, the owner included
 * @param mobCap       a mob upgrade stops spawning once this many of its kind are near the block
 */
public record Content(Map<IslandDimension, List<Weighted>> pools, Map<String, Upgrade> upgrades,
                      Map<IslandDimension, Long> unlockPrices, int shardEvery, long shardAmount, int teamSize,
                      int mobCap) {

    public record Weighted(Material material, int weight) {
    }

    public Content {
        EnumMap<IslandDimension, List<Weighted>> copied = new EnumMap<>(IslandDimension.class);
        pools.forEach((dimension, pool) -> copied.put(dimension, List.copyOf(pool)));
        pools = Map.copyOf(copied);
        upgrades = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(upgrades));
        unlockPrices = Map.copyOf(unlockPrices);
        shardEvery = Math.max(1, shardEvery);
        shardAmount = Math.max(0, shardAmount);
        teamSize = Math.max(1, teamSize);
        mobCap = Math.max(1, mobCap);
    }

    public List<Weighted> pool(IslandDimension dimension) {
        return pools.getOrDefault(dimension, List.of());
    }

    public Optional<Upgrade> upgrade(String id) {
        return Optional.ofNullable(id == null ? null : upgrades.get(id));
    }

    /** In the order the file lists them, for one dimension. */
    public List<Upgrade> upgradesFor(IslandDimension dimension) {
        return upgrades.values().stream().filter(upgrade -> upgrade.allowedIn(dimension)).toList();
    }

    public Collection<Upgrade> allUpgrades() {
        return upgrades.values();
    }

    public Optional<Long> unlockPrice(IslandDimension dimension) {
        return dimension == IslandDimension.OVERWORLD ? Optional.empty()
                : Optional.ofNullable(unlockPrices.get(dimension));
    }
}
