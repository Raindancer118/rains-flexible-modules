package de.raindancer.modules.oneblock.model;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Something an island buys for one dimension's block with shards, a level at a time.
 *
 * <p>A {@link Kind#BLOCK} or {@link Kind#MOB} upgrade takes part in the roll every break makes: its level's chance
 * mixes its block into what the one block turns into, or spawns its mob on top. The specials stay out of that roll —
 * an auto-smelt must not cost the block a chance of turning into something.
 *
 * @param block  for {@link Kind#BLOCK}; the icon otherwise
 * @param mob    for {@link Kind#MOB}
 * @param levels in order: level 1 is {@code levels.get(0)}
 */
public record Upgrade(String id, String name, Kind kind, Material block, EntityType mob,
                      Set<IslandDimension> dimensions, List<Level> levels) {

    public enum Kind {
        /** Mixes a block in. */
        BLOCK,
        /** Spawns a mob on top instead of changing the block. */
        MOB,
        /** Drops come out smelted, at the level's chance. */
        SMELT,
        /** The block breaks itself while somebody of the team is online, at the level's chance each second. */
        AUTO_BREAK;

        public boolean rolls() {
            return this == BLOCK || this == MOB;
        }

        public String wire() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        public static Optional<Kind> fromWire(String raw) {
            for (Kind kind : values()) {
                if (raw != null && kind.wire().equalsIgnoreCase(raw.strip().replace('_', '-'))) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * @param chance percent, 0–100: of a break for the roll, of a drop for smelting, of a second for auto-break
     * @param price  shards for reaching this level from the one below
     */
    public record Level(double chance, long price) {
    }

    public Upgrade {
        dimensions = Set.copyOf(dimensions);
        levels = List.copyOf(levels);
    }

    public boolean allowedIn(IslandDimension dimension) {
        return dimensions.contains(dimension);
    }

    public int maxLevel() {
        return levels.size();
    }

    /** Empty for level 0 and anything past the last. */
    public Optional<Level> level(int level) {
        return level < 1 || level > levels.size() ? Optional.empty() : Optional.of(levels.get(level - 1));
    }

    public double chanceAt(int level) {
        return level(level).map(Level::chance).orElse(0.0);
    }

    /** What reaching the next level costs; empty at the top. */
    public Optional<Long> priceOfNext(int current) {
        return level(current + 1).map(Level::price);
    }

    public Material icon() {
        return block;
    }
}
