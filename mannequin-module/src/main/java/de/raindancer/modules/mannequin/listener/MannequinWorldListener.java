package de.raindancer.modules.mannequin.listener;

import de.raindancer.modules.mannequin.service.MannequinService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.UUID;

/**
 * Re-spawning a world's mannequins when it loads, and letting go of the live entities — never the
 * stored records — when it unloads.
 */
public final class MannequinWorldListener implements IMannequinListener {

    private final MannequinService mannequins;

    public MannequinWorldListener(MannequinService mannequins) {
        this.mannequins = mannequins;
    }

    @EventHandler
    public void onLoad(WorldLoadEvent event) {
        mannequins.spawnAllIn(event.getWorld());
    }

    @EventHandler
    public void onUnload(WorldUnloadEvent event) {
        mannequins.despawnAllIn(event.getWorld().getName());
    }

    @Override
    public void forget(UUID player) {
        // Nothing per-player here: this listener only ever reacts to a world, never a player.
    }

    @Override
    public String describe() {
        return "spawning a world's mannequins when it loads, despawning them when it unloads";
    }
}
