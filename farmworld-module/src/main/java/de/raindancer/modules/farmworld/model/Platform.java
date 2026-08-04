package de.raindancer.modules.farmworld.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a farm world's spawn platform, as offsets from the middle.
 *
 * <h2>Why the shape is a value and the building is not</h2>
 * Because the shape is arithmetic — which blocks, in which ring, facing which way — and arithmetic that is
 * wrong is invisible until somebody is standing on a platform with a stair facing into it. Working it out
 * here means it can be counted in a test rather than looked at on a server, and the service that places the
 * blocks becomes a loop with no decisions in it.
 *
 * <h2>What it is</h2>
 * A 3×3 top, with a ring of stairs around it facing outwards so you can walk off in any direction, and a
 * solid skirt underneath so the platform is not floating. Somewhere to land that is flat, safe and obviously
 * built rather than found — which is the point: it is where the way back and the sign saying when the world
 * is regenerated go.
 */
public final class Platform {

    /** How far the flat top reaches from the middle. 1 gives the 3×3. */
    public static final int TOP_RADIUS = 1;

    /** The stair ring sits one block further out, and one block lower, so it is a step down. */
    public static final int STAIR_RADIUS = TOP_RADIUS + 1;

    private Platform() {
    }

    /** A block of the platform: where it goes, and which way it faces when it is a stair. */
    public record Block(int x, int y, int z, Kind kind, Facing facing) {

        /** Whether this block is part of the walkable top. */
        public boolean isTop() {
            return kind == Kind.TOP;
        }
    }

    /** What sort of block goes at a position. */
    public enum Kind {
        /** The flat 3×3 people stand on. */
        TOP,
        /** The ring around it, one lower, facing outwards. */
        STAIR,
        /** What holds the rest up, so the platform is not floating. */
        BASE
    }

    /** Which way a stair faces. Outwards from the middle, always. */
    public enum Facing {
        NORTH, EAST, SOUTH, WEST,
        /** A corner, where "outwards" is two directions at once — laid as a plain block instead. */
        NONE
    }

    /**
     * Every block of the platform, as offsets from the middle of the top.
     *
     * <p>The top first, then the stairs, then the base, which is the order they should be placed: a stair
     * needs something beside it to look attached, and the base under both.
     *
     * @param depth how many layers of base to put underneath
     */
    public static List<Block> blocks(int depth) {
        List<Block> blocks = new ArrayList<>();
        int layers = Math.max(1, Math.min(8, depth));

        for (int x = -TOP_RADIUS; x <= TOP_RADIUS; x++) {
            for (int z = -TOP_RADIUS; z <= TOP_RADIUS; z++) {
                blocks.add(new Block(x, 0, z, Kind.TOP, Facing.NONE));
            }
        }

        // The ring one out and one down, each facing away from the middle so stepping off is a step down
        // rather than a drop. Corners face nowhere: a stair there would point diagonally, which no stair can,
        // and the two choices both look wrong from one side.
        for (int x = -STAIR_RADIUS; x <= STAIR_RADIUS; x++) {
            for (int z = -STAIR_RADIUS; z <= STAIR_RADIUS; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) != STAIR_RADIUS) {
                    continue;
                }
                blocks.add(new Block(x, -1, z, Kind.STAIR, facingOutwards(x, z)));
            }
        }

        // Underneath everything, so nothing floats and nothing can be walked off into a hole.
        for (int layer = 1; layer <= layers; layer++) {
            for (int x = -STAIR_RADIUS; x <= STAIR_RADIUS; x++) {
                for (int z = -STAIR_RADIUS; z <= STAIR_RADIUS; z++) {
                    blocks.add(new Block(x, -1 - layer, z, Kind.BASE, Facing.NONE));
                }
            }
        }
        return List.copyOf(blocks);
    }

    /**
     * Which way a stair on the ring faces.
     *
     * <p>Outwards, so somebody walking off the platform walks down it. A corner is {@link Facing#NONE} —
     * see {@link #blocks}.
     */
    static Facing facingOutwards(int x, int z) {
        boolean onTheXEdge = Math.abs(x) == STAIR_RADIUS;
        boolean onTheZEdge = Math.abs(z) == STAIR_RADIUS;
        if (onTheXEdge && onTheZEdge) {
            return Facing.NONE;
        }
        if (onTheXEdge) {
            return x > 0 ? Facing.EAST : Facing.WEST;
        }
        return z > 0 ? Facing.SOUTH : Facing.NORTH;
    }

    /** Where a player should be put down: the middle of the top, standing on it. */
    public static Block standingSpot() {
        return new Block(0, 1, 0, Kind.TOP, Facing.NONE);
    }
}
