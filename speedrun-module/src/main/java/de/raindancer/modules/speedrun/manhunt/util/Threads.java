package de.raindancer.modules.speedrun.manhunt.util;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Where this module runs work that touches one entity. */
public final class Threads {

    private Threads() {
    }

    /**
     * On the thread owning {@code entity} — or right now, while {@code plugin} is shutting down.
     *
     * <p>Paper refuses to schedule anything for a disabled plugin, and a module is disabled from
     * inside its plugin's {@code onDisable}, where {@code isEnabled()} is already false. A hunt
     * ended by a restart would otherwise throw before a single compass or spectator was put back.
     * Nothing else is ticking by then, so running it in place is safe.
     */
    public static void entity(Plugin plugin, Entity entity, Runnable task) {
        if (plugin.isEnabled()) {
            Scheduling.entity(plugin, entity, task);
        } else {
            task.run();
        }
    }
}
