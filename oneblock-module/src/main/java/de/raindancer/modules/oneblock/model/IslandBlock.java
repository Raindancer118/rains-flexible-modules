package de.raindancer.modules.oneblock.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One dimension's one block: where it is, how often it has been broken, and which upgrades it carries at which level.
 *
 * @param upgrades upgrade id to level (1 and up); an absent id is not installed
 */
public record IslandBlock(BlockSpot spot, long broken, Map<String, Integer> upgrades) {

    public IslandBlock {
        // In the order they were bought: the shared roll walks them in this order, and Map.copyOf would give each
        // start of the server another one.
        upgrades = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(upgrades));
    }

    public static IslandBlock fresh(BlockSpot spot) {
        return new IslandBlock(spot, 0, Map.of());
    }

    public int level(String upgrade) {
        return upgrades.getOrDefault(upgrade, 0);
    }

    public IslandBlock brokenOnceMore() {
        return new IslandBlock(spot, broken + 1, upgrades);
    }

    public IslandBlock withLevel(String upgrade, int level) {
        Map<String, Integer> changed = new LinkedHashMap<>(upgrades);
        if (level <= 0) {
            changed.remove(upgrade);
        } else {
            changed.put(upgrade, level);
        }
        return new IslandBlock(spot, broken, changed);
    }
}
