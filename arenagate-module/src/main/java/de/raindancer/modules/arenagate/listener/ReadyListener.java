package de.raindancer.modules.arenagate.listener;

import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.arena.api.ArenaProtocol;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.event.EventHandler;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Once this server has finished starting — worlds loaded, every plugin up — the arena is told, so whoever waited in its
 * hub through the restart comes back. Asked again every five seconds for two minutes, until the arena answers: the link
 * may still be finding its feet at that moment.
 */
public final class ReadyListener implements IGateListener {

    private static final LogChannel log = Log.of("arenagate");
    private static final int TRIES = 24;

    private final Plugin plugin;
    private final ServerLink link;

    public ReadyListener(Plugin plugin, ServerLink link) {
        this.plugin = plugin;
        this.link = link;
    }

    @EventHandler
    public void onLoaded(ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
            return;
        }
        AtomicInteger tries = new AtomicInteger();
        ScheduledTask[] timer = new ScheduledTask[1];
        timer[0] = Scheduling.globalTimer(plugin, 20L, 100L, task -> {
            if (tries.incrementAndGet() > TRIES) {
                task.cancel();
                log.warn("The arena never heard that this server is up; whoever waited there has to come back by hand.");
                return;
            }
            link.request(ArenaProtocol.SMP_READY, Map.of(), Duration.ofSeconds(4)).whenComplete((answer, failure) -> {
                if (failure == null && "true".equals(answer.get("ok"))) {
                    task.cancel();
                }
            });
        });
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered per player here.
    }
}
