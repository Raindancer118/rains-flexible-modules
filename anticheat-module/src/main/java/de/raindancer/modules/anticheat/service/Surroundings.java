package de.raindancer.modules.anticheat.service;

import de.raindancer.modules.anticheat.rules.Physics;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Shulker;
import org.bukkit.util.BoundingBox;

/**
 * What is around a player's box at one position, read from the world on the player's own thread.
 * Collisions come from the server's own collision code ({@code hasCollisionsIn}), so slabs, carpets,
 * fences, boats and shulkers all count exactly as vanilla counts them.
 *
 * @param special anything that changes vertical physics: liquid, ladders, webs, powder snow, bubble
 *                columns, honey walls, scaffolding
 */
public record Surroundings(boolean ground, boolean ceiling, boolean inside, boolean liquid, boolean climbable,
                           boolean web, boolean powderSnow, boolean bubble, boolean honey, boolean overLiquid,
                           boolean special, boolean bouncy, Material below, double friction, double jumpFactor) {

    private static final double SKIN = 0.001;

    public static Surroundings at(World world, double x, double y, double z, double width, double height) {
        double half = width / 2;
        boolean ground = world.hasCollisionsIn(new BoundingBox(x - half + SKIN, y - 0.03, z - half + SKIN,
                x + half - SKIN, y, z + half - SKIN));
        boolean ceiling = world.hasCollisionsIn(new BoundingBox(x - half + SKIN, y + height, z - half + SKIN,
                x + half - SKIN, y + height + 0.15, z + half - SKIN));
        boolean inside = world.hasCollisionsIn(new BoundingBox(x - half + 0.06, y + 0.06, z - half + 0.06,
                x + half - 0.06, y + height - 0.06, z + half - 0.06));

        boolean liquid = false;
        boolean climbable = false;
        boolean web = false;
        boolean powderSnow = false;
        boolean bubble = false;
        boolean honey = false;
        boolean overLiquid = false;
        int minX = floor(x - half - 0.1);
        int maxX = floor(x + half + 0.1);
        int minZ = floor(z - half - 0.1);
        int maxZ = floor(z + half + 0.1);
        int minY = floor(y - 0.6);
        int maxY = floor(y + height);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = minY; by <= maxY; by++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    Material type = block.getType();
                    if (type.isAir()) {
                        continue;
                    }
                    boolean wet = isLiquid(type) || waterlogged(block.getBlockData());
                    boolean belowFeet = by < floor(y) || (by == floor(y) && y - by > 0.95);
                    if (belowFeet) {
                        overLiquid |= wet;
                        bubble |= type == Material.BUBBLE_COLUMN;
                        climbable |= type == Material.SCAFFOLDING;
                        continue;
                    }
                    liquid |= wet;
                    climbable |= Tag.CLIMBABLE.isTagged(type) || type == Material.SCAFFOLDING || Tag.TRAPDOORS.isTagged(type);
                    web |= type == Material.COBWEB || type == Material.SWEET_BERRY_BUSH;
                    powderSnow |= type == Material.POWDER_SNOW;
                    bubble |= type == Material.BUBBLE_COLUMN;
                    honey |= type == Material.HONEY_BLOCK;
                }
            }
        }
        Material below = world.getBlockAt(floor(x), floor(y - 0.5000001), floor(z)).getType();
        boolean bouncy = below == Material.SLIME_BLOCK || Tag.BEDS.isTagged(below);
        boolean special = liquid || climbable || web || powderSnow || bubble || honey;
        return new Surroundings(ground, ceiling, inside, liquid, climbable, web, powderSnow, bubble, honey,
                overLiquid, special, bouncy, below, Physics.friction(below), Physics.jumpFactor(below));
    }

    public Surroundings withGround(boolean ground) {
        return new Surroundings(ground, ceiling, inside, liquid, climbable, web, powderSnow, bubble, honey,
                overLiquid, special, bouncy, below, friction, jumpFactor);
    }

    /** Boats, shulkers, minecarts and happy ghasts can be stood on, and they move under the player. */
    public static boolean nearRideableGround(World world, double x, double y, double z) {
        BoundingBox around = new BoundingBox(x - 2, y - 2.5, z - 2, x + 2, y + 1, z + 2);
        for (Entity entity : world.getNearbyEntities(around)) {
            if (entity instanceof Boat || entity instanceof Shulker || entity instanceof Minecart
                    || entity.getType().name().equals("HAPPY_GHAST")) {
                return true;
            }
        }
        return false;
    }

    /** Living things close enough to push. */
    public static int pushers(World world, double x, double y, double z, Entity self) {
        int count = 0;
        BoundingBox around = new BoundingBox(x - 1, y, z - 1, x + 1, y + 2, z + 1);
        for (Entity entity : world.getNearbyEntities(around)) {
            if (entity != self && entity instanceof org.bukkit.entity.LivingEntity && !(entity instanceof org.bukkit.entity.ArmorStand)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isLiquid(Material type) {
        return type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN
                || type == Material.KELP || type == Material.KELP_PLANT || type == Material.SEAGRASS
                || type == Material.TALL_SEAGRASS;
    }

    private static boolean waterlogged(BlockData data) {
        return data instanceof Waterlogged logged && logged.isWaterlogged();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
