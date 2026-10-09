package de.raindancer.modules.farmlimit.service;

import de.raindancer.modules.farmlimit.model.Crowd;
import de.raindancer.modules.farmlimit.model.Hotspots;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Locale;

/** The counting, at the Bukkit border. No decisions here — {@code FarmRule} makes them. */
public final class FarmCounter {

    /**
     * Animals near {@code where}. Runs on the spawn's own region thread, which owns everything within
     * the radius the settings allow (at most 64 blocks).
     */
    public Crowd around(Location where, EntityType kind, int radius) {
        int same = 0;
        int animals = 0;
        for (Entity nearby : where.getNearbyEntities(radius, radius, radius)) {
            if (nearby.getType() == kind) {
                same++;
            }
            if (nearby instanceof Breedable) {
                animals++;
            }
        }
        return new Crowd(same, animals);
    }

    /** Every loaded non-player entity, by chunk. Only on a single-threaded server: on Folia no thread owns every world. */
    public Hotspots everywhere(Iterable<World> worlds) {
        Hotspots tally = new Hotspots();
        for (World world : worlds) {
            String key = world.getKey().asString();
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    continue;
                }
                Location at = entity.getLocation();
                tally.count(key, at.getBlockX() >> 4, at.getBlockZ() >> 4,
                        entity.getType().getKey().getKey().toLowerCase(Locale.ROOT));
            }
        }
        return tally;
    }
}
