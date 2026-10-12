package de.raindancer.modules.arena.listener;

import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.util.Builders;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.ItemSpawnEvent;
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

    @Override
    public void forget(UUID player) {
        // Nothing is remembered: every answer is worked out from where somebody is at the moment they act.
    }
}
