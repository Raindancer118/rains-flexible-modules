package de.raindancer.modules.claims;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Wall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Recomputes the connection state of fences, walls and panes.
 * <p>
 * Placing a block with {@code setBlockData(material.createBlockData(), false)} yields the
 * <em>default</em> state — for a fence that means no connections at all, which is exactly why a
 * plugin-built fence comes out as a row of disconnected posts. Vanilla normally derives the state
 * during placement and on neighbour updates; neither happens for a direct data write with physics off.
 * <p>
 * Enabling physics is not a fix either: it makes neighbours re-evaluate, but the freshly written block
 * keeps its own empty state, and physics on thousands of blocks is expensive besides. So the state is
 * computed explicitly here, mirroring vanilla's rules.
 */
public final class BlockConnector {

    private static final List<BlockFace> HORIZONTAL =
            List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);

    private BlockConnector() {
    }

    /** True for the block types whose appearance depends on their neighbours. */
    public static boolean isConnectable(Material material) {
        BlockData data = material.createBlockData();
        return data instanceof Wall || data instanceof MultipleFacing;
    }

    /**
     * Recomputes {@code block} and every neighbour that also connects.
     * <p>
     * Both directions matter: the new block needs to know about its surroundings, and an existing fence
     * next to it needs to grow a connection towards the new one.
     */
    public static void connect(Block block) {
        apply(block);
        for (BlockFace face : HORIZONTAL) {
            Block neighbour = block.getRelative(face);
            if (isConnectable(neighbour.getType())) {
                apply(neighbour);
            }
        }
        Block above = block.getRelative(BlockFace.UP);
        if (above.getBlockData() instanceof Wall) {
            apply(above);
        }
        Block below = block.getRelative(BlockFace.DOWN);
        if (below.getBlockData() instanceof Wall) {
            apply(below);
        }
    }

    /**
     * Recomputes a whole batch plus its neighbours, each block only once.
     * <p>
     * Used after a fence run so a long row is stitched together in one pass instead of being rewritten
     * once per neighbour.
     */
    public static void connectAll(Collection<Block> blocks) {
        Set<Block> touched = new LinkedHashSet<>();
        for (Block block : blocks) {
            touched.add(block);
            for (BlockFace face : HORIZONTAL) {
                Block neighbour = block.getRelative(face);
                if (isConnectable(neighbour.getType())) {
                    touched.add(neighbour);
                }
            }
            Block above = block.getRelative(BlockFace.UP);
            if (above.getBlockData() instanceof Wall) {
                touched.add(above);
            }
            Block below = block.getRelative(BlockFace.DOWN);
            if (below.getBlockData() instanceof Wall) {
                touched.add(below);
            }
        }
        for (Block block : touched) {
            apply(block);
        }
    }

    private static void apply(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Wall wall) {
            applyWall(block, wall);
        } else if (data instanceof MultipleFacing facing) {
            applyMultipleFacing(block, facing);
        }
    }

    private static void applyMultipleFacing(Block block, MultipleFacing facing) {
        Set<BlockFace> allowed = facing.getAllowedFaces();
        boolean changed = false;
        for (BlockFace face : HORIZONTAL) {
            if (!allowed.contains(face)) {
                continue;
            }
            boolean connect = connectsTo(block, face);
            if (facing.hasFace(face) != connect) {
                facing.setFace(face, connect);
                changed = true;
            }
        }
        if (changed) {
            block.setBlockData(facing, false);
        }
    }

    private static void applyWall(Block block, Wall wall) {
        boolean changed = false;
        int connections = 0;
        boolean north = false;
        boolean south = false;
        boolean east = false;
        boolean west = false;

        for (BlockFace face : HORIZONTAL) {
            boolean connect = connectsTo(block, face);
            // TALL when something sits above that side, otherwise LOW — matches vanilla closely enough
            // that a plugin-built wall is indistinguishable from a hand-built one.
            Wall.Height height = !connect
                    ? Wall.Height.NONE
                    : (isTallSide(block, face) ? Wall.Height.TALL : Wall.Height.LOW);
            if (wall.getHeight(face) != height) {
                wall.setHeight(face, height);
                changed = true;
            }
            if (connect) {
                connections++;
                switch (face) {
                    case NORTH -> north = true;
                    case SOUTH -> south = true;
                    case EAST -> east = true;
                    default -> west = true;
                }
            }
        }

        // Vanilla drops the centre post only when the wall runs straight through with nothing on top.
        boolean straight = (north && south && !east && !west) || (east && west && !north && !south);
        boolean up = !straight || connections == 0 || needsPostForBlockAbove(block);
        if (wall.isUp() != up) {
            wall.setUp(up);
            changed = true;
        }
        if (changed) {
            block.setBlockData(wall, false);
        }
    }

    private static boolean needsPostForBlockAbove(Block block) {
        Block above = block.getRelative(BlockFace.UP);
        Material type = above.getType();
        if (type.isAir()) {
            return false;
        }
        // A wall continuing upwards, or anything solid resting on top, needs the post.
        return above.getBlockData() instanceof Wall || type.isSolid();
    }

    private static boolean isTallSide(Block block, BlockFace face) {
        Block above = block.getRelative(BlockFace.UP);
        if (above.getBlockData() instanceof Wall) {
            return true;
        }
        Block diagonal = block.getRelative(face).getRelative(BlockFace.UP);
        return diagonal.getBlockData() instanceof Wall;
    }

    /**
     * Vanilla's connection rule: same family of fence/wall, a gate turned the right way, or a solid face.
     */
    private static boolean connectsTo(Block block, BlockFace face) {
        Block neighbour = block.getRelative(face);
        Material own = block.getType();
        Material other = neighbour.getType();
        if (other.isAir()) {
            return false;
        }

        BlockData otherData = neighbour.getBlockData();

        // A fence gate only counts when it is turned across the fence line.
        if (otherData instanceof Gate gate && otherData instanceof Directional directional) {
            BlockFace gateFacing = directional.getFacing();
            boolean acrossTheLine = gateFacing == rotate(face) || gateFacing == rotate(face).getOppositeFace();
            return acrossTheLine || gate.isInWall();
        }

        if (Tag.WALLS.isTagged(own)) {
            // Walls join other walls and fences, and anything with a solid side.
            if (Tag.WALLS.isTagged(other) || Tag.FENCES.isTagged(other)) {
                return true;
            }
            return isSturdy(other);
        }

        if (Tag.FENCES.isTagged(own)) {
            // Wooden fences and nether brick fence are separate families and do not join each other.
            if (Tag.FENCES.isTagged(other)) {
                return sameFenceFamily(own, other);
            }
            if (Tag.WALLS.isTagged(other)) {
                return true;
            }
            return isSturdy(other);
        }

        // Glass panes and iron bars: same block or a solid neighbour.
        if (otherData instanceof MultipleFacing) {
            return true;
        }
        return isSturdy(other);
    }

    private static boolean sameFenceFamily(Material own, Material other) {
        boolean ownWooden = Tag.WOODEN_FENCES.isTagged(own);
        boolean otherWooden = Tag.WOODEN_FENCES.isTagged(other);
        return ownWooden == otherWooden;
    }

    /** A face counts as sturdy when the block is a full, solid, non-transparent cube. */
    private static boolean isSturdy(Material material) {
        if (!material.isSolid()) {
            return false;
        }
        // Slabs, stairs, fences and the like are solid but must not be connected to on every side;
        // occluding is the closest API-level stand-in for vanilla's isFaceSturdy check.
        return material.isOccluding();
    }

    private static BlockFace rotate(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    /** Every fence and wall material a claim owner may pick, walls after fences. */
    public static List<Material> barrierMaterials() {
        List<Material> materials = new ArrayList<>();
        List<Material> fences = new ArrayList<>(Tag.FENCES.getValues());
        List<Material> walls = new ArrayList<>(Tag.WALLS.getValues());
        fences.removeIf(material -> material.name().startsWith("LEGACY_"));
        walls.removeIf(material -> material.name().startsWith("LEGACY_"));
        fences.sort((left, right) -> left.name().compareTo(right.name()));
        walls.sort((left, right) -> left.name().compareTo(right.name()));
        materials.addAll(fences);
        materials.addAll(walls);
        return materials;
    }
}
