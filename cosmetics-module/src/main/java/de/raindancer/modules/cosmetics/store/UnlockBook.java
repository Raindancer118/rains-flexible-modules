package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What each player has bought, in {@code unlocks.yml}. Held in memory — a menu asks on every draw — and written
 * whole on every purchase. A file that could not be read is never written over: buying is refused until it is fixed.
 */
public final class UnlockBook {

    private final YamlStore store;
    private final Map<UUID, Set<String>> unlocks = new HashMap<>();
    private volatile boolean readable = true;

    public UnlockBook(YamlStore store) {
        this.store = store;
    }

    public synchronized void load() {
        unlocks.clear();
        YamlConfiguration yaml = store.read();
        readable = store.problems().isEmpty();
        ConfigurationSection all = yaml.getConfigurationSection("unlocks");
        if (all == null) {
            return;
        }
        for (String key : all.getKeys(false)) {
            try {
                unlocks.put(UUID.fromString(key), new HashSet<>(all.getStringList(key)));
            } catch (IllegalArgumentException notAPlayer) {
                // Skipped; the rest still count.
            }
        }
    }

    public boolean readable() {
        return readable;
    }

    public synchronized boolean has(UUID player, String key) {
        Set<String> mine = unlocks.get(player);
        return mine != null && mine.contains(key);
    }

    public synchronized Set<String> of(UUID player) {
        return Set.copyOf(unlocks.getOrDefault(player, Set.of()));
    }

    /** Records a purchase. @return false, and nothing changed, when it could not be saved */
    public synchronized boolean add(UUID player, String key) {
        if (!readable) {
            return false;
        }
        boolean added = unlocks.computeIfAbsent(player, ignored -> new HashSet<>()).add(key);
        if (!added || save()) {
            return true;
        }
        unlocks.get(player).remove(key);
        return false;
    }

    private boolean save() {
        Map<UUID, Set<String>> snapshot = new HashMap<>();
        unlocks.forEach((player, keys) -> snapshot.put(player, Set.copyOf(keys)));
        return store.write(yaml -> snapshot.forEach((player, keys) ->
                yaml.set("unlocks." + player, keys.stream().sorted().toList())));
    }
}
