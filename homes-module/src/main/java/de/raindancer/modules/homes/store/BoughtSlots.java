package de.raindancer.modules.homes.store;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Home slots players paid for, kept per player in {@code bought-slots.yml}.
 *
 * <p>Money was taken for these, so a count is never lost on a failed write: {@link #add} reports it and
 * leaves the count where it was, and the caller gives the money back.
 */
public final class BoughtSlots {

    private final YamlStore file;
    private final Map<UUID, Integer> counts = new HashMap<>();

    public BoughtSlots(Path file) {
        this.file = new YamlStore(file);
    }

    public synchronized void load() {
        counts.clear();
        ConfigurationSection section = file.read().getConfigurationSection("bought");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                int count = section.getInt(key);
                if (count > 0) {
                    counts.put(UUID.fromString(key), count);
                }
            } catch (IllegalArgumentException notAnId) {
                // Skipped; the file is only ever written by this class.
            }
        }
    }

    public synchronized int of(UUID player) {
        return counts.getOrDefault(player, 0);
    }

    /** One more for this player. @return whether it reached the disk; false leaves the count as it was */
    public synchronized boolean add(UUID player) {
        int before = of(player);
        counts.put(player, before + 1);
        boolean written = file.write(yaml -> counts.forEach((who, count) ->
                yaml.set("bought." + who, count)));
        if (!written) {
            if (before == 0) {
                counts.remove(player);
            } else {
                counts.put(player, before);
            }
        }
        return written;
    }
}
