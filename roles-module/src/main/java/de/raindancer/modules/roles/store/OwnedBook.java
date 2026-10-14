package de.raindancer.modules.roles.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
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
 * Which roles a player bought or rents, in {@code owned.yml}. Held in memory. Like {@link ChoiceBook} it is a
 * document of the network ({@code YamlStore.network}), a change writes only its own entry, {@link #watch()}
 * reloads when another server writes, and a document that could not be read is never written over.
 */
public final class OwnedBook {

    private final YamlStore store;
    private final Map<UUID, Map<String, Ownership>> owned = new LinkedHashMap<>();
    private volatile boolean readable = true;

    public OwnedBook(YamlStore store) {
        this.store = store;
    }

    /** Reads the document; a read that fails keeps what is held and refuses changes until one succeeds. */
    public synchronized void load() {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            readable = false;
            return;
        }
        readable = true;
        owned.clear();
        ConfigurationSection all = read.get().getConfigurationSection("owned");
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

    /** Reloads when another server writes the document. Close to stop. */
    public NetworkBus.Subscription watch() {
        return store.onChange(this::load);
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
        if (!writable()) {
            return false;
        }
        String path = "owned." + ownership.player() + "." + ownership.role();
        if (!store.update(yaml -> {
            yaml.set(path + ".kind", ownership.kind().name().toLowerCase(Locale.ROOT));
            yaml.set(path + ".due", ownership.dueAt());
        })) {
            return false;
        }
        owned.computeIfAbsent(ownership.player(), ignored -> new LinkedHashMap<>()).put(ownership.role(), ownership);
        return true;
    }

    /** Forgets one ownership. @return false, and nothing changed, when it could not be saved */
    public synchronized boolean remove(UUID player, String role) {
        if (!writable()) {
            return false;
        }
        Map<String, Ownership> mine = owned.get(player);
        if (mine == null || mine.get(role) == null) {
            return true;
        }
        String path = "owned." + player + "." + role;
        if (!store.update(yaml -> yaml.set(path, null))) {
            return false;
        }
        mine.remove(role);
        return true;
    }

    private boolean writable() {
        if (!readable) {
            load();
        }
        return readable;
    }
}
