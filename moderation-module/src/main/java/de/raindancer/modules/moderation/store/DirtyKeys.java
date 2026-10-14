package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The keys of a network store this server changed and has not written yet. Only those are written, in one
 * {@link YamlStore#update}, so what other servers did to the other keys is never put back to what this
 * server last saw.
 */
final class DirtyKeys {

    private final Set<String> keys = new LinkedHashSet<>();

    synchronized void mark(String key) {
        keys.add(key);
    }

    synchronized boolean has(String key) {
        return keys.contains(key);
    }

    /** @return whether everything marked reached the store; what did not stays marked for the next try */
    synchronized boolean write(YamlStore store, BiConsumer<YamlConfiguration, String> put) {
        if (keys.isEmpty()) {
            return true;
        }
        List<String> taking = List.copyOf(keys);
        if (!store.update(yaml -> taking.forEach(key -> put.accept(yaml, key)))) {
            return false;
        }
        keys.removeAll(taking);
        return true;
    }
}
