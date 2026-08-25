package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.Ground;
import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import de.raindancer.core.world.safety.Spot;

import java.util.Set;

/**
 * Where the ground actually is under one column, and what is standing on it.
 *
 * <h2>Why this is not "the topmost block that is not air"</h2>
 * That was the old answer, and it is how a road through a forest came out laid across the treetops:
 * a leaf block is not air, so the scan stopped at the canopy and paved eight blocks above the soil.
 * The same mistake puts a road on top of tall grass, on a snow layer that melts, and on the surface
 * of the sea.
 *
 * <p>So the scan skips everything that is <em>standing on</em> the ground rather than being it —
 * foliage, ground cover, snow — and reports water and lava as what they are rather than as
 * something to build on. What a road then does about water is not decided here: it is decided by
 * {@link RouteProfiler}, which can see the whole crossing rather than one column of it.
 */
public final class TerrainReader {

    /** How far down a column is worth scanning before calling it empty. */
    private static final int CEILING_Y = 320;
    private static final int FLOOR_Y = -64;

    /** Things that sit on the ground rather than being it. Matched by suffix as well as by name. */
    private static final Set<String> PASSABLE_EXACT = Set.of(
            "AIR", "CAVE_AIR", "VOID_AIR", "SNOW", "POWDER_SNOW", "VINE", "GLOW_LICHEN", "SCULK_VEIN",
            "SHORT_GRASS", "GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH", "SEAGRASS",
            "TALL_SEAGRASS", "KELP", "KELP_PLANT", "SUGAR_CANE", "BAMBOO", "CACTUS", "LILY_PAD",
            "COBWEB", "TORCH", "WALL_TORCH", "SOUL_TORCH", "LANTERN", "CHAIN", "SNOW_BLOCK",
            "MOSS_CARPET", "PINK_PETALS", "SWEET_BERRY_BUSH", "BROWN_MUSHROOM", "RED_MUSHROOM",
            "NETHER_SPROUTS", "WARPED_ROOTS", "CRIMSON_ROOTS", "HANGING_ROOTS", "BIG_DRIPLEAF",
            "SMALL_DRIPLEAF", "SPORE_BLOSSOM", "AZALEA", "FLOWERING_AZALEA", "MANGROVE_ROOTS");

    private static final Set<String> PASSABLE_SUFFIXES = Set.of(
            "_LEAVES", "_LOG", "_WOOD", "_SAPLING", "_FLOWER", "_TULIP", "_ORCHID", "_BUSH",
            "_CARPET", "_STEM", "_HYPHAE", "_CORAL", "_CORAL_FAN", "_CORAL_BLOCK", "_MUSHROOM_BLOCK",
            "_BANNER", "_SIGN", "_FENCE", "_FENCE_GATE", "_WALL", "_BUTTON", "_PRESSURE_PLATE",
            "_DOOR", "_TRAPDOOR", "_CANDLE");

    private static final Set<String> WATER = Set.of("WATER", "ICE", "FROSTED_ICE", "BLUE_ICE",
            "PACKED_ICE", "BUBBLE_COLUMN");

    private static final Set<String> LAVA = Set.of("LAVA");

    /** What one column looks like from top to bottom. */
    public record Reading(Column column, int groundY, boolean isUnderWater, int waterSurfaceY,
                          boolean isLava, boolean isVoid) {

        /** How deep the water is over the bed, or {@code 0} on dry land. */
        public int waterDepth() {
            return isUnderWater ? Math.max(0, waterSurfaceY - groundY + 1) : 0;
        }

        /** The height a road would sit at if it simply followed the ground here. */
        public int walkableY() {
            return groundY;
        }
    }

    public int floorY() {
        return FLOOR_Y;
    }

    /**
     * Reads one column.
     *
     * <p>Top down, and it stops at the first block that is actually ground — which means a cave
     * hollowed out below the surface is never mistaken for it, the way a bottom-up scan would.
     */
    public Reading read(Ground ground, String world, Column column) {
        int waterTop = Integer.MIN_VALUE;
        boolean sawLava = false;

        for (int y = CEILING_Y; y >= FLOOR_Y; y--) {
            String material = ground.materialAt(new Spot(world, column.x(), y, column.z()));
            if (material == null) {
                continue;
            }
            String upper = material.toUpperCase(java.util.Locale.ROOT);
            if (WATER.contains(upper)) {
                if (waterTop == Integer.MIN_VALUE) {
                    waterTop = y;
                }
                continue;
            }
            if (LAVA.contains(upper)) {
                sawLava = true;
                continue;
            }
            if (isPassable(upper)) {
                continue;
            }
            return new Reading(column, y + 1, waterTop != Integer.MIN_VALUE, waterTop, sawLava, false);
        }
        return new Reading(column, FLOOR_Y, false, Integer.MIN_VALUE, sawLava, true);
    }

    private static boolean isPassable(String material) {
        if (PASSABLE_EXACT.contains(material)) {
            return true;
        }
        for (String suffix : PASSABLE_SUFFIXES) {
            if (material.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this material is one a road may simply be laid over rather than needing cleared. */
    public boolean isClearable(String material) {
        if (material == null) {
            return false;
        }
        String upper = material.toUpperCase(java.util.Locale.ROOT);
        return isPassable(upper) || WATER.contains(upper);
    }
}
