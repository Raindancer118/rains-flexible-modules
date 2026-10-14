package de.raindancer.modules.roles.store;

import de.raindancer.core.data.store.KeyedDocument;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.roles.model.Ownership;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which roles a player bought or rents, in {@code owned.yml}. Held in memory. Like {@link ChoiceBook} a document of
 * the network, one player per key ({@link KeyedDocument}): a change starts from what the document holds for that
 * player now, so two servers changing them both count.
 */
public final class OwnedBook {

    private final KeyedDocument<Map<String, Ownership>> document;

    public OwnedBook(YamlStore store) {
        this.document = new KeyedDocument<>(store, "owned", OwnedBook::read, OwnedBook::write);
    }

    private static Map<String, Ownership> read(ConfigurationSection yaml, String key) {
        ConfigurationSection roles = yaml.getConfigurationSection(key);
        UUID player;
        try {
            player = UUID.fromString(key);
        } catch (IllegalArgumentException notAPlayer) {
            return null;
        }
        if (roles == null) {
            return null;
        }
        Map<String, Ownership> mine = new LinkedHashMap<>();
        for (String role : roles.getKeys(false)) {
            ConfigurationSection each = roles.getConfigurationSection(role);
            if (each == null) {
                continue;
            }
            Ownership.Kind kind = "rented".equalsIgnoreCase(each.getString("kind"))
                    ? Ownership.Kind.RENTED : Ownership.Kind.BOUGHT;
            mine.put(role, new Ownership(player, role, kind, each.getLong("due", 0L)));
        }
        return mine.isEmpty() ? null : mine;
    }

    private static void write(ConfigurationSection yaml, String path, Map<String, Ownership> mine) {
        mine.values().forEach(ownership -> {
            yaml.set(path + "." + ownership.role() + ".kind", ownership.kind().name().toLowerCase(Locale.ROOT));
            yaml.set(path + "." + ownership.role() + ".due", ownership.dueAt());
        });
    }

    /** Reads the document; a read that fails keeps what is held and refuses changes until one succeeds. */
    public void load() {
        document.load();
    }

    /** Told when another server changed somebody's roles (they are read in already). Close to stop. */
    public NetworkBus.Subscription watch() {
        return document.onChange(changed -> { });
    }

    public boolean readable() {
        return document.readable();
    }

    public Optional<Ownership> of(UUID player, String role) {
        return role == null ? Optional.empty() : Optional.ofNullable(mine(player).get(role));
    }

    public List<Ownership> of(UUID player) {
        return new ArrayList<>(mine(player).values());
    }

    private Map<String, Ownership> mine(UUID player) {
        return player == null ? Map.of() : document.get(player.toString()).orElse(Map.of());
    }

    /** Records an ownership. @return false, and nothing changed, when it could not be saved */
    public boolean put(Ownership ownership) {
        return document.change(ownership.player().toString(), now -> {
            Map<String, Ownership> mine = now == null ? new LinkedHashMap<>() : new LinkedHashMap<>(now);
            mine.put(ownership.role(), ownership);
            return mine;
        });
    }

    /** Forgets one ownership. @return false, and nothing changed, when it could not be saved */
    public boolean remove(UUID player, String role) {
        if (document.readable() && of(player, role).isEmpty()) {
            return true;
        }
        return document.change(player.toString(), now -> {
            if (now == null) {
                return null;
            }
            Map<String, Ownership> mine = new LinkedHashMap<>(now);
            mine.remove(role);
            return mine.isEmpty() ? null : mine;
        });
    }
}
