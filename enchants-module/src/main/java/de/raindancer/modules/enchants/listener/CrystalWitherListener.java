package de.raindancer.modules.enchants.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.enchants.rules.CrystalWither;
import de.raindancer.modules.enchants.service.ShardMobService;
import de.raindancer.modules.enchants.service.ShardWorldService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

/** The Crystal Wither: a wither built of amethyst blocks in the shard world rises when its last skull is placed. */
public final class CrystalWitherListener implements IEnchantsListener {

    private final Plugin plugin;
    private final ShardWorldService worlds;
    private final ShardMobService mobs;
    private final de.raindancer.core.world.protection.Land land;

    public CrystalWitherListener(Plugin plugin, ShardWorldService worlds, ShardMobService mobs,
                                 de.raindancer.core.world.protection.Land land) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.mobs = mobs;
        this.land = land;
    }

    private static CrystalWither.Part part(Block block) {
        Material type = block.getType();
        if (type == Material.AMETHYST_BLOCK) {
            return CrystalWither.Part.AMETHYST;
        }
        if (type == Material.WITHER_SKELETON_SKULL || type == Material.WITHER_SKELETON_WALL_SKULL) {
            return CrystalWither.Part.SKULL;
        }
        return type.isAir() ? CrystalWither.Part.AIR : CrystalWither.Part.OTHER;
    }

    /**
     * Whether whoever placed the last skull may break every block the build is made of: it is cleared when the wither
     * rises, and a build reaching into somebody else's land must not take their blocks with it.
     */
    private boolean mayTakeApart(org.bukkit.entity.Player builder, World world, CrystalWither.Found found) {
        for (int[] at : found.blocks()) {
            if (!land.can(builder, new Location(world, at[0], at[1], at[2]),
                    de.raindancer.core.world.protection.LandAction.BREAK)) {
                return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        World world = placed.getWorld();
        if (part(placed) != CrystalWither.Part.SKULL || !worlds.isShardWorld(world)) {
            return;
        }
        // A tick later, once the skull is really there — and still checked then.
        Scheduling.regionLater(plugin, placed.getLocation(), 1L, () -> CrystalWither
                .find((x, y, z) -> part(world.getBlockAt(x, y, z)), placed.getX(), placed.getY(), placed.getZ())
                .filter(found -> mayTakeApart(event.getPlayer(), world, found))
                .ifPresent(found -> {
                    for (int[] at : found.blocks()) {
                        world.getBlockAt(at[0], at[1], at[2]).setType(Material.AIR);
                    }
                    mobs.crystalWither(new Location(world, found.base()[0], found.base()[1], found.base()[2]));
                }));
    }
}
