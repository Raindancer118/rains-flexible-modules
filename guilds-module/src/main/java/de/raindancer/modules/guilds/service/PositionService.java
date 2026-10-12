package de.raindancer.modules.guilds.service;

import de.raindancer.modules.guilds.GuildProgressSettings;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Guild;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where everybody was when last looked at, for the together bonus. Sampled on each player's own thread every few
 * seconds and read from anywhere — reading a live location of another player from an event thread is not safe on
 * Folia, and a few seconds old is near enough for "are they playing together".
 */
public final class PositionService implements IGuildsService {

    private record Spot(String world, double x, double z) {
    }

    private final Map<UUID, Spot> seen = new ConcurrentHashMap<>();
    private volatile int radius;

    public PositionService(GuildProgressSettings settings) {
        progressSettings(settings);
    }

    @Override
    public void settings(GuildsSettings settings) {
        // The radius is a progress setting; see progressSettings.
    }

    public void progressSettings(GuildProgressSettings settings) {
        this.radius = settings.togetherRadius();
    }

    public void saw(UUID player, String world, double x, double z) {
        seen.put(player, new Spot(world, x, z));
    }

    public void forget(UUID player) {
        seen.remove(player);
    }

    /** How many other members of the guild were within the radius of this one, in the same world. */
    public int guildmatesNear(UUID player, Guild guild) {
        Spot here = seen.get(player);
        if (here == null) {
            return 0;
        }
        double most = (double) radius * radius;
        int count = 0;
        for (UUID member : guild.members().keySet()) {
            if (member.equals(player)) {
                continue;
            }
            Spot there = seen.get(member);
            if (there != null && there.world().equals(here.world())) {
                double dx = there.x() - here.x();
                double dz = there.z() - here.z();
                if (dx * dx + dz * dz <= most) {
                    count++;
                }
            }
        }
        return count;
    }
}
