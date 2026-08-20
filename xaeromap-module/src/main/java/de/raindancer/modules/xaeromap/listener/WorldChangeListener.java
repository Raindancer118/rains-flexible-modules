package de.raindancer.modules.xaeromap.listener;

import de.raindancer.modules.xaeromap.service.WorldIdService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.UUID;

/**
 * Tells the client which world it is in again, whenever that has changed.
 *
 * <p>Both events matter. Walking through a nether portal is a world change; dying in the nether and
 * respawning at a bed in the overworld is not always reported as one, and a client that missed the
 * change files everything it draws next under the world it thinks it is still in — which is a map of
 * the nether written over the overworld's, permanently, in the client's own cache.
 */
public final class WorldChangeListener implements IXaeroMapListener {

    private final WorldIdService worldIds;

    public WorldChangeListener(WorldIdService worldIds) {
        this.worldIds = worldIds;
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        worldIds.send(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        worldIds.send(event.getPlayer());
    }

    @Override
    public void forget(UUID player) {
        // Nothing kept.
    }
}
