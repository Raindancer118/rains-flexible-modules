package de.raindancer.modules.wallsroads.listener;

import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.WallsRoadsServices;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import de.raindancer.modules.wallsroads.rules.ProtectRule;
import de.raindancer.modules.wallsroads.util.PermissionNodes;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Keeps a standing wall or road standing.
 *
 * <p>Not decoration: a build is undone from a snapshot of what was there before, so a wall somebody
 * has mined a hole in no longer matches its own record, and tearing it down afterwards fills their
 * hole with blocks that were never there. See {@link ProtectRule}.
 *
 * <p>Explosions are filtered rather than cancelled — cancelling the event stops the whole blast,
 * including the half of it that was over open ground and nothing to do with this module.
 */
public final class StructureProtectionListener implements IWallsRoadsListener {

    private final WallsRoadsServices services;
    private final ProtectRule rule = new ProtectRule();

    public StructureProtectionListener(WallsRoadsServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!mayChange(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            services.messages().send(event.getPlayer(), "wallsroads.protected");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!mayChange(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
            services.messages().send(event.getPlayer(), "wallsroads.protected");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::belongsToAStructure);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::belongsToAStructure);
    }

    private boolean belongsToAStructure(Block block) {
        return services.service().occupancy().ownerOf(spotOf(block)).isPresent();
    }

    private boolean mayChange(Player player, Block block) {
        return rule.mayChange(services.service().occupancy(), spotOf(block), this::ownerOf,
                player.getUniqueId(), player.hasPermission(PermissionNodes.MANAGE_ANY));
    }

    private Optional<UUID> ownerOf(String structureId) {
        Optional<Wall> wall = services.registry().wall(structureId);
        if (wall.isPresent()) {
            return Optional.ofNullable(wall.get().owner());
        }
        return services.registry().road(structureId).map(RoadPath::owner);
    }

    private static Spot spotOf(Block block) {
        return new Spot(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }
}
