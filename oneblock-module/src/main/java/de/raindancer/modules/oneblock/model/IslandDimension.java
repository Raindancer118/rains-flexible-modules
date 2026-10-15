package de.raindancer.modules.oneblock.model;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

import java.util.Locale;
import java.util.Optional;

/**
 * The three islands an island owns: the overworld one it starts with, and a nether and an end that are unlocked.
 * Each is a void world of its own with its own one block.
 */
public enum IslandDimension {

    OVERWORLD(World.Environment.NORMAL, "", Material.GRASS_BLOCK, "plains", BlockSpot.START),
    NETHER(World.Environment.NETHER, "_nether", Material.NETHERRACK, "nether_wastes", BlockSpot.START),
    // Away from 0/0: an end world builds its exit portal there on the first tick, on top of whatever stands at 0/0.
    END(World.Environment.THE_END, "_end", Material.END_STONE, "the_end", new BlockSpot(0, 64, 40));

    private final World.Environment environment;
    private final String worldSuffix;
    private final Material icon;
    private final String biome;
    private final BlockSpot start;

    IslandDimension(World.Environment environment, String worldSuffix, Material icon, String biome, BlockSpot start) {
        this.environment = environment;
        this.worldSuffix = worldSuffix;
        this.icon = icon;
        this.biome = biome;
        this.start = start;
    }

    /** Where a fresh island's block of this dimension stands. */
    public BlockSpot start() {
        return start;
    }

    public World.Environment environment() {
        return environment;
    }

    public String worldSuffix() {
        return worldSuffix;
    }

    public Material icon() {
        return icon;
    }

    public NamespacedKey biome() {
        return NamespacedKey.minecraft(biome);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The message key of its name. */
    public String nameKey() {
        return "oneblock.dimension." + id();
    }

    public static Optional<IslandDimension> byId(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String wanted = raw.strip().toLowerCase(Locale.ROOT);
        for (IslandDimension dimension : values()) {
            if (dimension.id().equals(wanted)) {
                return Optional.of(dimension);
            }
        }
        // What people type for the other two.
        return switch (wanted) {
            case "the_end", "theend" -> Optional.of(END);
            case "the_nether", "hell" -> Optional.of(NETHER);
            case "world", "normal", "home" -> Optional.of(OVERWORLD);
            default -> Optional.empty();
        };
    }
}
