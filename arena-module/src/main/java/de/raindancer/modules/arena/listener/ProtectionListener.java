package de.raindancer.modules.arena.listener;

import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.util.Builders;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;

import java.util.UUID;

/**
 * What the arena adds to RainsCore's locked worlds, which keep every block and entity of its maps as built: no item entity
 * exists in an arena world at all — whatever would appear is stopped, which closes every way an item could leave a
 * fight that a list might miss — and no portal takes anybody anywhere.
 */
public final class ProtectionListener implements IArenaListener {

    private final ArenaServices services;

    public ProtectionListener(ArenaServices services) {
        this.services = services;
    }

    private boolean arena(World world) {
        return world != null && services.arena().isArenaWorld(world.getName());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (arena(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (arena(event.getFrom().getWorld()) && !Builders.isBuilding(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * A fighter whose kit builds is let build by Core for the whole arena world, as the lock asks only about the player;
     * the places are this side's to name: only inside the pit that is open, which is the part put back after the round.
     */
    private boolean outsideTheOpenPit(Player player, Location where) {
        return arena(player.getWorld()) && !Builders.isBuilding(player)
                && services.matches().mayBuild(player.getUniqueId()) && !services.arena().isPitOpen(where);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (outsideTheOpenPit(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (outsideTheOpenPit(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (outsideTheOpenPit(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (outsideTheOpenPit(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null && outsideTheOpenPit(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && outsideTheOpenPit(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Doors, levers, buttons and the like outside the open pit stay shut to a fighter; the air and the pit are theirs. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null && outsideTheOpenPit(event.getPlayer(), event.getClickedBlock().getLocation())) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered: every answer is worked out from where somebody is at the moment they act.
    }
}
