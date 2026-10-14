package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.cosmetics.CosmeticsSettings;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.model.TeleportLookChoice;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Notices when a player's cosmetics data changes under the module — on a network, when Core puts what they
 * chose on another server onto them, which happens a moment <em>after</em> they join — and checks it again
 * the way a join used to: a particle or teleport look they may not have here comes off, and the teleport look
 * Core is handed is the one the data holds.
 */
public final class DataWatch implements ICosmeticsService {

    private record Seen(TeleportLookChoice look, ParticleChoice worn, ParticleChoice wings) {
    }

    private static final long PERIOD_TICKS = 20;

    private final ParticleService particles;
    private final TeleportLookService teleports;
    private final BooleanSupplier following;
    private final Map<UUID, Seen> seen = new ConcurrentHashMap<>();
    private volatile ScheduledTask timer;

    /**
     * @param following whether this server shows another one's data (1.x link): what they may wear is then
     *                  decided there, so the data is taken as it is
     */
    public DataWatch(ParticleService particles, TeleportLookService teleports, BooleanSupplier following) {
        this.particles = particles;
        this.teleports = teleports;
        this.following = following;
    }

    @Override
    public void settings(CosmeticsSettings fresh) {
        // Reads nothing itself: the services it calls hold the settings.
    }

    public synchronized void start(Plugin plugin, Server server) {
        stop();
        timer = Scheduling.globalTimer(plugin, PERIOD_TICKS, PERIOD_TICKS, task -> {
            for (Player player : server.getOnlinePlayers()) {
                Scheduling.entity(plugin, player, () -> check(player));
            }
        });
    }

    public synchronized void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        seen.clear();
    }

    /** On the player's own thread: looks again if their data is not what it was at the last look. */
    public void check(Player who) {
        Seen now = read(who);
        if (now.equals(seen.get(who.getUniqueId()))) {
            return;
        }
        if (following.getAsBoolean()) {
            teleports.refresh(who);
        } else {
            particles.revalidate(who);
            teleports.load(who);
        }
        seen.put(who.getUniqueId(), read(who));
    }

    public void forget(UUID who) {
        seen.remove(who);
    }

    private Seen read(Player who) {
        return new Seen(teleports.stored(who), particles.current(who), particles.wings(who));
    }
}
