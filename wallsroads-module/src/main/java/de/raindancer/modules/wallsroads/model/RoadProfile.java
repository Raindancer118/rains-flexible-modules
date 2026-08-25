package de.raindancer.modules.wallsroads.model;

import org.bukkit.Material;

/**
 * What a road is made of besides its paving: kerbs, lamps, railings, piers, tunnel lining.
 *
 * <p>Separate from {@link RoadPath} because it is the part an owner picks once and reuses — the
 * difference between a dirt track and a lit highway is this record, not the road's shape. The
 * presets are the whole point: nobody wants to choose eight materials to lay a path across a field.
 *
 * @param kerb          edging along both sides, or {@code null} for none
 * @param lamp          the light itself, or {@code null} for an unlit road
 * @param lampPost      what the lamp stands on
 * @param lampSpacing   blocks between lamps, measured along the road
 * @param railing       the parapet on a bridge, so nothing walks off the side of it
 * @param support       the piers a bridge stands on
 * @param tunnelLining  what a bored tunnel's walls and ceiling are faced with
 * @param tunnelLight   what lights an enclosed stretch
 * @param glass         the shell of an underwater tunnel
 * @param headroom      clear blocks above the surface, so a road under a hill can be ridden through
 * @param tunnelFloor   what an enclosed stretch is floored with, or {@code null} to keep the paving —
 *                      an underwater tube floored in gravel is a tube you cannot see out of the
 *                      bottom of, and the point of glass is the view
 * @param woodenSupports whether piers and lamp posts are built from the wood growing in that biome
 *                      rather than from {@code support}
 */
public record RoadProfile(Material kerb, Material lamp, Material lampPost, int lampSpacing,
                          Material railing, Material support, Material tunnelLining,
                          Material tunnelLight, Material glass, int headroom,
                          Material tunnelFloor, boolean woodenSupports) {

    public RoadProfile {
        lampSpacing = Math.max(2, Math.min(64, lampSpacing));
        headroom = Math.max(2, Math.min(16, headroom));
    }

    /** A track: paving and nothing else. What a road is when somebody has not asked for more. */
    public static RoadProfile plain() {
        return new RoadProfile(null, null, null, 12,
                Material.OAK_FENCE, Material.OAK_LOG, Material.STONE_BRICKS,
                Material.LANTERN, Material.GLASS, 4,
                Material.SMOOTH_STONE, true);
    }

    /** A made road: kerbs, and a lantern often enough to walk it at night without a torch. */
    public static RoadProfile lit() {
        return new RoadProfile(Material.STONE_SLAB, Material.LANTERN, Material.OAK_LOG, 10,
                Material.OAK_FENCE, Material.OAK_LOG, Material.STONE_BRICKS,
                Material.LANTERN, Material.GLASS, 4,
                Material.SMOOTH_STONE, true);
    }

    /** A highway: stone kerbs, sea lanterns, stone-brick railings and piers. */
    public static RoadProfile grand() {
        return new RoadProfile(Material.POLISHED_ANDESITE_SLAB, Material.SEA_LANTERN,
                Material.STONE_BRICK_WALL, 8,
                Material.STONE_BRICK_WALL, Material.STONE_BRICKS, Material.POLISHED_ANDESITE,
                Material.SEA_LANTERN, Material.GLASS, 5,
                Material.POLISHED_ANDESITE, false);
    }

    public boolean hasKerb() {
        return kerb != null;
    }

    public boolean isLit() {
        return lamp != null;
    }
}
