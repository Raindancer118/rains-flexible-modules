package de.raindancer.modules.farmworld.visual;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.farmworld.model.Platform;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;

/**
 * Building the platform a farm world's spawn sits on.
 *
 * <h2>Why a farm world gets one at all</h2>
 * Because a world's spawn is wherever the generator put it, and that is as likely to be a hillside, a cave
 * roof or the middle of an ocean as anywhere else. Somebody arriving there lands in whatever was generated,
 * which is the opposite of the one predictable place a farm world should have.
 *
 * <p>So the module builds one: a 3×3 top with stairs around it, flat and obviously made. It is where the
 * signs, the portals home and whatever else an admin wants go — and it is what makes the plain
 * {@code /farm mining} worth having instead of everybody being scattered.
 *
 * <h2>What is here and what is not</h2>
 * The shape is {@link Platform}'s, worked out as arithmetic and counted by a test. This is the loop that
 * turns it into blocks, which is the part that needs a world and therefore cannot be tested — so there are
 * deliberately no decisions in it beyond which material is which.
 */
public final class SpawnPlatform {

    /** What the top is made of. Stone brick: obviously built, and not worth mining. */
    private static final Material TOP = Material.STONE_BRICKS;
    private static final Material STAIR = Material.STONE_BRICK_STAIRS;
    private static final Material BASE = Material.STONE_BRICKS;

    /** What is cleared above the platform, so nobody arrives inside a hill. */
    private static final int HEADROOM = 4;

    /** How many layers of stone go underneath, so it is not floating over a cave. */
    private static final int DEPTH = 3;

    private final LogChannel log;

    public SpawnPlatform(LogChannel log) {
        this.log = log;
    }

    /**
     * Builds the platform at this world's spawn, and moves the spawn onto it.
     *
     * <p>Main thread only — it places blocks. Called after a farm world is made and after it is regenerated,
     * because a regenerated world is fresh terrain and whatever was built is gone with it.
     *
     * @return whether it was built
     */
    public boolean buildAt(World world) {
        if (world == null) {
            return false;
        }
        try {
            Location middle = world.getSpawnLocation();
            // Onto a sensible height rather than wherever the spawn happened to land: the highest block at
            // those coordinates is the surface, which is where a platform belongs.
            int surface = world.getHighestBlockYAt(middle.getBlockX(), middle.getBlockZ());
            int y = Math.max(world.getMinHeight() + DEPTH + 2,
                    Math.min(world.getMaxHeight() - HEADROOM - 2, surface + 1));

            for (Platform.Block piece : Platform.blocks(DEPTH)) {
                Block at = world.getBlockAt(middle.getBlockX() + piece.x(),
                        y + piece.y(),
                        middle.getBlockZ() + piece.z());
                place(at, piece);
            }
            clearAbove(world, middle.getBlockX(), y, middle.getBlockZ());

            // The spawn itself onto the middle of the top, so the server's own idea of where people arrive
            // agrees with the thing that was just built.
            Platform.Block standing = Platform.standingSpot();
            world.setSpawnLocation(middle.getBlockX() + standing.x(),
                    y + standing.y(),
                    middle.getBlockZ() + standing.z());
            return true;
        } catch (RuntimeException failure) {
            // Never at the cost of the farm world. A platform that could not be built is a cosmetic loss;
            // a farm world that failed to be created because of one is not.
            log.warn(failure, "Could not build the spawn platform in '{}'.", world.getName());
            return false;
        }
    }

    private void place(Block at, Platform.Block piece) {
        switch (piece.kind()) {
            case TOP -> at.setType(TOP, false);
            case BASE -> at.setType(BASE, false);
            case STAIR -> {
                if (piece.facing() == Platform.Facing.NONE) {
                    // A corner. No stair can point diagonally, so it is laid as a full block — see Platform.
                    at.setType(TOP, false);
                    return;
                }
                at.setType(STAIR, false);
                BlockData data = at.getBlockData();
                if (data instanceof Stairs stairs) {
                    stairs.setFacing(faceOf(piece.facing()));
                    at.setBlockData(stairs, false);
                }
            }
        }
    }

    /**
     * Air above the platform.
     *
     * <p>Because the spawn may well be inside a hill, and a platform built into one is a platform nobody can
     * stand on. Only the footprint, and only a few blocks up: this is making room to arrive, not levelling
     * the landscape.
     */
    private void clearAbove(World world, int x, int y, int z) {
        for (int up = 1; up <= HEADROOM; up++) {
            for (int dx = -Platform.STAIR_RADIUS; dx <= Platform.STAIR_RADIUS; dx++) {
                for (int dz = -Platform.STAIR_RADIUS; dz <= Platform.STAIR_RADIUS; dz++) {
                    world.getBlockAt(x + dx, y + up, z + dz).setType(Material.AIR, false);
                }
            }
        }
    }

    private static BlockFace faceOf(Platform.Facing facing) {
        return switch (facing) {
            case NORTH -> BlockFace.NORTH;
            case EAST -> BlockFace.EAST;
            case SOUTH -> BlockFace.SOUTH;
            case WEST -> BlockFace.WEST;
            case NONE -> BlockFace.NORTH;
        };
    }

    /** What this draws, for the diagnostic that lists what the module does to a world. */
    public String describe() {
        return "the platform at a farm world's spawn";
    }
}
