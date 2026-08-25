package de.raindancer.modules.wallsroads.model;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * What a road is actually paved with: a family of related blocks rather than one repeated forever.
 *
 * <h2>Why a mix at all</h2>
 * A road of one block reads as a texture stretched over the ground — which is exactly what the first
 * one built on the test server looked like, a grey stripe of gravel. Every hand-built road anybody
 * makes in this game mixes stone, cobble and andesite, because the variation is what makes it read as
 * something laid down block by block.
 *
 * <h2>Deterministic, not random</h2>
 * The block for a place is a hash of that place. A road rebuilt after a change comes back identical
 * rather than reshuffled, two roads meeting at a junction agree about the blocks they share, and the
 * teardown snapshot matches what was actually laid.
 */
public record PavingPalette(List<String> mix, String slab) {

    /** The families. Weighted by repetition, which is the whole of the tuning. */
    private static final Map<Material, PavingPalette> FAMILIES = Map.of(
            Material.STONE, new PavingPalette(List.of(
                    "STONE", "STONE", "STONE", "STONE",
                    "COBBLESTONE", "COBBLESTONE",
                    "ANDESITE", "ANDESITE",
                    "POLISHED_ANDESITE",
                    "STONE_BRICKS"), "STONE_SLAB"),

            Material.STONE_BRICKS, new PavingPalette(List.of(
                    "STONE_BRICKS", "STONE_BRICKS", "STONE_BRICKS", "STONE_BRICKS",
                    "CRACKED_STONE_BRICKS", "MOSSY_STONE_BRICKS",
                    "STONE", "ANDESITE",
                    "POLISHED_ANDESITE"), "STONE_BRICK_SLAB"),

            Material.COBBLESTONE, new PavingPalette(List.of(
                    "COBBLESTONE", "COBBLESTONE", "COBBLESTONE", "COBBLESTONE",
                    "MOSSY_COBBLESTONE", "STONE",
                    "ANDESITE", "GRAVEL"), "COBBLESTONE_SLAB"),

            Material.GRAVEL, new PavingPalette(List.of(
                    "GRAVEL", "GRAVEL", "GRAVEL", "GRAVEL",
                    "COBBLESTONE", "COBBLESTONE",
                    "STONE", "ANDESITE"), "COBBLESTONE_SLAB"),

            Material.DIRT_PATH, new PavingPalette(List.of(
                    "DIRT_PATH", "DIRT_PATH", "DIRT_PATH", "DIRT_PATH",
                    "COARSE_DIRT", "COARSE_DIRT",
                    "DIRT", "GRAVEL"), "COBBLESTONE_SLAB"),

            Material.DEEPSLATE_BRICKS, new PavingPalette(List.of(
                    "DEEPSLATE_BRICKS", "DEEPSLATE_BRICKS", "DEEPSLATE_BRICKS",
                    "CRACKED_DEEPSLATE_BRICKS", "DEEPSLATE_TILES",
                    "POLISHED_DEEPSLATE", "COBBLED_DEEPSLATE"), "DEEPSLATE_BRICK_SLAB"),

            Material.POLISHED_ANDESITE, new PavingPalette(List.of(
                    "POLISHED_ANDESITE", "POLISHED_ANDESITE", "POLISHED_ANDESITE",
                    "ANDESITE", "STONE", "SMOOTH_STONE"), "POLISHED_ANDESITE_SLAB"),

            Material.SANDSTONE, new PavingPalette(List.of(
                    "SANDSTONE", "SANDSTONE", "SANDSTONE",
                    "SMOOTH_SANDSTONE", "CUT_SANDSTONE", "SAND"), "SANDSTONE_SLAB"));

    /**
     * The family this material belongs to.
     *
     * <p>A material with none is simply itself — somebody who chose emerald blocks for their road
     * meant emerald blocks, and guessing at a family for it would be inventing one.
     */
    public static PavingPalette forMaterial(Material material) {
        PavingPalette known = FAMILIES.get(material);
        if (known != null) {
            return known;
        }
        return new PavingPalette(List.of(material.name()), material.name());
    }

    /** What goes at this place. */
    public String at(Column column) {
        if (mix.size() == 1) {
            return mix.get(0);
        }
        return mix.get(Math.floorMod(scatter(column.x(), column.z()), mix.size()));
    }

    /**
     * A hash with no visible pattern in it.
     *
     * <p>Multiplying the coordinates by two large odd numbers and folding the bits: the obvious
     * {@code (x + z) % n} lays diagonal stripes across the road, which is worse than no variation.
     */
    private static int scatter(int x, int z) {
        int hash = x * 0x1f1f1f1f ^ z * 0x27d4eb2d;
        hash ^= hash >>> 15;
        hash *= 0x85ebca6b;
        hash ^= hash >>> 13;
        return hash;
    }
}
