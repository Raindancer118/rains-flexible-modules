package de.raindancer.modules.veintoggle.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.veintoggle.model.BlockKey;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** {@link VeinUndoService.Threads} on a real server, Folia's regions included. */
public final class ServerThreads implements VeinUndoService.Threads {

    private final Plugin plugin;

    public ServerThreads(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void region(World world, BlockKey at, Runnable task) {
        Scheduling.region(plugin, at.centre(world), task);
    }

    @Override
    public void player(Player player, Runnable task, Runnable gone) {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            task.run();
            return;
        }
        if (player.getScheduler().run(plugin, ignored -> task.run(), gone) == null) {
            gone.run();
        }
    }

    @Override
    public void later(long ticks, Runnable task) {
        Scheduling.globalLater(plugin, ticks, task);
    }
}
