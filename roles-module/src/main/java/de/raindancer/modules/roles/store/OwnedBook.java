package de.raindancer.modules.roles.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.roles.model.Ownership;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which roles a player bought or rents, in {@code owned.yml}. Held in memory and written whole on every change,
 * which is rare. Like {@link ChoiceBook}, a file that could not be read is never written over.
 */
public final class OwnedBook {

    private final YamlStore store;
    private final Map<UUID, Map<String, Ownership>> owned = new LinkedHashMap<>();
    private volatile boolean readable = true;

    public OwnedBook(YamlStore store) {
        this.store = store;
    }

    public synchronized void load() {
        owned.clear();
        YamlConfiguration yaml = store.read();
        readable = store.problems().isEmpty();
        ConfigurationSection all = yaml.getConfigurationSection("owned");
        if (all == null) {
            return;
        }
        for (String key : all.getKeys(false)) {
            ConfigurationSection roles = all.getConfigurationSection(key);
            if (roles == null) {
                continue;
            }
            try {
                UUID player = UUID.fromString(key);
                for (String role : roles.getKeys(false)) {
                    ConfigurationSection each = roles.getConfigurationSection(role);
                    if (each == null) {
                        continue;
                    }
                    Ownership.Kind kind = "rented".equalsIgnoreCase(each.getString("kind"))
                            ? Ownership.Kind.RENTED : Ownership.Kind.BOUGHT;
                    owned.computeIfAbsent(player, ignored -> new LinkedHashMap<>())
                            .put(role, new Ownership(player, role, kind, each.getLong("due", 0L)));
                }
            } catch (IllegalArgumentException notAPlayer) {
                // Skipped; the rest still count.
            }
        }
    }

    public boolean readable() {
        return readable;
    }

    public synchronized Optional<Ownership> of(UUID player, String role) {
        Map<String, Ownership> mine = owned.get(player);
        return Optional.ofNullable(mine == null || role == null ? null : mine.get(role));
    }

    public synchronized List<Ownership> of(UUID player) {
        Map<String, Ownership> mine = owned.get(player);
        return mine == null ? List.of() : new ArrayList<>(mine.values());
    }

    /** Records an ownership. @return false, and nothing changed, when it could not be saved */
    public synchronized boolean put(Ownership ownership) {
        if (!readable) {
            return false;
        }
        Map<String, Ownership> mine = owned.computeIfAbsent(ownership.player(), ignored -> new LinkedHashMap<>());
        Ownership before = mine.put(ownership.role(), ownership);
        if (save()) {
            return true;
        }
        if (before == null) {
            mine.remove(ownership.role());
        } else {
            mine.put(ownership.role(), before);
        }
        return false;
    }

    /** Forgets one ownership. @return false, and nothing changed, when it could not be saved */
    public synchronized boolean remove(UUID player, String role) {
        if (!readable) {
            return false;
        }
        Map<String, Ownership> mine = owned.get(player);
        Ownership before = mine == null ? null : mine.remove(role);
        if (before == null || save()) {
            return true;
        }
        mine.put(role, before);
        return false;
    }

    private boolean save() {
        List<Ownership> snapshot = owned.values().stream().flatMap(each -> each.values().stream()).toList();
        return store.write(yaml -> snapshot.forEach(ownership -> {
            String path = "owned." + ownership.player() + "." + ownership.role();
            yaml.set(path + ".kind", ownership.kind().name().toLowerCase(Locale.ROOT));
            yaml.set(path + ".due", ownership.dueAt());
        }));
    }
}
