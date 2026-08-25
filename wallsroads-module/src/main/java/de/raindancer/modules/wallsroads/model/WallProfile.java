package de.raindancer.modules.wallsroads.model;

import org.bukkit.Material;

/**
 * What a wall is besides a slab of stone: footings, a wall-walk to stand on, battlements to stand
 * behind, buttresses down its face and a way up.
 *
 * <p>The same reasoning as {@link RoadProfile}: the shape of a wall and the <em>character</em> of one
 * are different decisions, and a wall that is only a height and a thickness comes out as an extruded
 * rectangle no matter how carefully it was marked.
 *
 * @param battlements     whether the parapet on the outer edge is crenellated
 * @param merlonPeriod    blocks between the gaps in it
 * @param walkway         whether the top is a wall-walk — a floor with the parapets either side of it
 * @param walkwayMaterial what that floor is made of
 * @param foundation      whether the wall is carried down to the ground under it
 * @param buttressMaterial what the buttresses down the outer face are built from, or {@code null}
 * @param buttressSpacing blocks along the wall between them
 * @param ladderSpacing   blocks along the wall between ways up, or {@code 0} for a wall with none
 * @param towerMaterial   what a tower is built from, or {@code null} for a wall without towers
 * @param towerSpacing    blocks along the wall between towers
 * @param towerRise       how far a tower stands above the walk
 * @param towerWidth      how far a tower stands out from the wall, each way
 * @param plinth          whether a base course runs along the foot of the outer face, proud of it
 * @param cornice         whether the course under the wall-walk corbels outward to carry it
 * @param lantern         what is set into that cornice for light, or {@code null} for an unlit wall
 * @param lanternSpacing  blocks along the wall between those lights
 * @param arches          whether the face between two buttresses is recessed into an arched panel
 */
public record WallProfile(boolean battlements, int merlonPeriod, boolean walkway,
                          Material walkwayMaterial, boolean foundation, Material buttressMaterial,
                          int buttressSpacing, int ladderSpacing, Material towerMaterial,
                          int towerSpacing, int towerRise, int towerWidth,
                          boolean plinth, boolean cornice, Material lantern, int lanternSpacing,
                          boolean arches) {

    public WallProfile {
        merlonPeriod = Math.max(2, Math.min(8, merlonPeriod));
        buttressSpacing = Math.max(3, Math.min(64, buttressSpacing));
        ladderSpacing = Math.max(0, Math.min(128, ladderSpacing));
        towerSpacing = Math.max(8, Math.min(128, towerSpacing));
        towerRise = Math.max(0, Math.min(32, towerRise));
        towerWidth = Math.max(1, Math.min(6, towerWidth));
        lanternSpacing = Math.max(2, Math.min(64, lanternSpacing));
    }

    /** A boundary wall: what a wall is when nobody asked for more. */
    public static WallProfile simple() {
        return new WallProfile(false, 2, false, Material.STONE_BRICK_SLAB, false, null,
                8, 0, null, 24, 3, 2,
                false, false, null, 8, false);
    }

    /**
     * A town wall: footed, a wall-walk with a parapet either side, crenellated on the outside,
     * buttressed down the face, with a ladder up every so often.
     */
    public static WallProfile town() {
        return new WallProfile(true, 2, true, Material.STONE_BRICKS, true, Material.STONE_BRICKS,
                8, 24, null, 24, 3, 2,
                true, true, Material.LANTERN, 8, false);
    }

    /** A fortress: the town wall, with towers at the corners and along the long runs. */
    public static WallProfile fortress() {
        return new WallProfile(true, 2, true, Material.STONE_BRICKS, true, Material.STONE_BRICKS,
                6, 16, Material.STONE_BRICKS, 24, 4, 2,
                true, true, Material.LANTERN, 6, false);
    }

    public boolean hasTowers() {
        return towerMaterial != null;
    }

    public boolean hasButtresses() {
        return buttressMaterial != null;
    }

    public boolean hasLadders() {
        return ladderSpacing > 0;
    }

    public boolean isLit() {
        return lantern != null;
    }
}
