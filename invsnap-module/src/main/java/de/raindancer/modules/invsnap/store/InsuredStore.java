package de.raindancer.modules.invsnap.store;

import de.raindancer.core.data.store.YamlStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Who has opted in to death insurance — one small file, read once and kept in memory. */
public final class InsuredStore {

    private final YamlStore file;
    private final Set<UUID> insured = ConcurrentHashMap.newKeySet();

    public InsuredStore(Path dataFolder) {
        this.file = new YamlStore(dataFolder.resolve("insured.yml"));
        for (String raw : file.read().getStringList("players")) {
            try {
                insured.add(UUID.fromString(raw));
            } catch (IllegalArgumentException notAUuid) {
                // A hand-edited line: skip it rather than lose everybody else's choice.
            }
        }
    }

    public boolean isInsured(UUID player) {
        return insured.contains(player);
    }

    public synchronized void set(UUID player, boolean on) {
        boolean changed = on ? insured.add(player) : insured.remove(player);
        if (!changed) {
            return;
        }
        List<String> all = insured.stream().map(UUID::toString).sorted().toList();
        file.update(yaml -> yaml.set("players", all));
    }
}
