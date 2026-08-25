package de.raindancer.modules.wallsroads.model;

import org.bukkit.Material;

/**
 * What a wall is besides a slab of stone: footings, a walkway to stand on, battlements to stand
 * behind, and towers.
 *
 * <p>The same reasoning as {@link RoadProfile}: the shape of a wall and the <em>character</em> of
 * one are different decisions, made at different times, and a wall that is only a height and a
 * thickness comes out as an extruded rectangle no matter how carefully it was marked.
 *
 * @param battlements   whether the top row is crenellated
 * @param merlonPeriod  blocks between the gaps in it
 * @param walkway       whether a ledge runs along the inside, one below the top
 * @param walkwayMaterial what that ledge is made of
 * @param foundation    whether the wall is carried down to the ground under it
 * @param towerMaterial what a tower is built from, or {@code null} for a wall without towers
 * @param towerSpacing  blocks along the wall between towers
 * @param towerRise     how far a tower stands above the wall
 * @param towerWidth    how far a tower stands out from the wall, each way
 */
public record WallProfile(boolean battlements, int merlonPeriod, boolean walkway,
                          Material walkwayMaterial, boolean foundation, Material towerMaterial,
                          int towerSpacing, int towerRise, int towerWidth) {

    public WallProfile {
        merlonPeriod = Math.max(2, Math.min(8, merlonPeriod));
        towerSpacing = Math.max(8, Math.min(128, towerSpacing));
        towerRise = Math.max(0, Math.min(32, towerRise));
        towerWidth = Math.max(1, Math.min(6, towerWidth));
    }

    /** A boundary wall: what a wall is when nobody asked for more. */
    public static WallProfile simple() {
        return new WallProfile(false, 2, false, Material.STONE_BRICK_SLAB, false, null, 24, 3, 2);
    }

    /** A town wall: footed, crenellated, with a walkway behind the parapet. */
    public static WallProfile town() {
        return new WallProfile(true, 2, true, Material.STONE_BRICK_SLAB, true, null, 24, 3, 2);
    }

    /** A fortress: the town wall, with towers at the corners and along the long runs. */
    public static WallProfile fortress() {
        return new WallProfile(true, 2, true, Material.STONE_BRICK_SLAB, true,
                Material.STONE_BRICKS, 24, 4, 2);
    }

    public boolean hasTowers() {
        return towerMaterial != null;
    }
}
