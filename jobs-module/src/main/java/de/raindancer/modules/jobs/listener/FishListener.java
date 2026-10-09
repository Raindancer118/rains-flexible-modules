package de.raindancer.modules.jobs.listener;

import de.raindancer.modules.jobs.JobsServices;
import de.raindancer.modules.jobs.util.PermissionNodes;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerFishEvent;

import java.util.UUID;

/** Every fish reeled in counts for the fishing goals — only fish, not the boots and the saddles. */
public final class FishListener implements IJobsListener {

    private final JobsServices services;

    public FishListener(JobsServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)
                || !event.getPlayer().hasPermission(PermissionNodes.USE)) {
            return;
        }
        services.goals().caught(event.getPlayer(), item.getItemStack());
    }

    @Override
    public void forget(UUID player) {
        // Remembers nobody.
    }
}
