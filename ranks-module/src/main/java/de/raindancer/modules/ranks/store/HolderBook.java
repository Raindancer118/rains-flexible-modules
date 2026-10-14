package de.raindancer.modules.ranks.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.ranks.model.Holder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Who holds which rank and how they want it shown, in {@code holders.yml}. Read on every chat line and every
 * tablist and nametag pass, so held in memory and readable from any thread.
 *
 * <h2>On a network</h2>
 * One document for every server ({@code YamlStore.network}): a rank is the player's wherever they play. A change
 * is made to the player as the document has them <em>now</em> and writes only that player, so two servers
 * changing different players — or different parts of one — lose nothing of each other's. {@link #watch()}
 * reloads when another server writes.
 *
 * <p>A document that could not be read is never written over, and a failed read keeps what is held: until it
 * can be read nothing can change, because the change could not be applied to what is really there.
 */
public final class HolderBook {

    private final YamlStore store;
    private final Map<UUID, Holder> holders = new ConcurrentHashMap<>();
    private volatile boolean readable = true;

    public HolderBook(YamlStore store) {
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
        Map<UUID, Holder> fresh = new java.util.HashMap<>();
        ConfigurationSection all = read.get().getConfigurationSection("holders");
        if (all != null) {
            for (String key : all.getKeys(false)) {
                try {
                    UUID player = UUID.fromString(key);
                    holderOf(player, all.getConfigurationSection(key)).ifPresent(holder -> fresh.put(player, holder));
                } catch (IllegalArgumentException notAPlayer) {
                    // Skipped; the rest still count.
                }
            }
        }
        holders.keySet().retainAll(fresh.keySet());
        holders.putAll(fresh);
    }

    /** Reloads when another server writes the document. Close to stop. */
    public NetworkBus.Subscription watch() {
        return store.onChange(this::load);
    }

    private static Optional<Holder> holderOf(UUID player, ConfigurationSection each) {
        if (each == null) {
            return Optional.empty();
        }
        Holder holder = new Holder(player, new LinkedHashSet<>(each.getStringList("ranks")),
                each.getString("wears", ""), each.getBoolean("show.chat", true),
                each.getBoolean("show.nametag", true), each.getBoolean("show.tablist", true));
        return holder.isBlank() ? Optional.empty() : Optional.of(holder);
    }

    public boolean readable() {
        return readable;
    }

    public Holder of(UUID player) {
        Holder held = player == null ? null : holders.get(player);
        return held != null ? held : Holder.fresh(player);
    }

    public Optional<Holder> find(UUID player) {
        return Optional.ofNullable(player == null ? null : holders.get(player));
    }

    public Map<UUID, Holder> all() {
        return Map.copyOf(holders);
    }

    /** Changes one player. @return false, and nothing changed, when it could not be saved */
    public synchronized boolean change(UUID player, UnaryOperator<Holder> change) {
        if (player == null) {
            return false;
        }
        if (!readable) {
            load();
            if (!readable) {
                return false;
            }
        }
        String path = "holders." + player;
        // Applied to the document's own picture of the player: another server may have changed them since this one read.
        Holder[] after = new Holder[1];
        boolean written = store.update(yaml -> {
            Holder current = holderOf(player, yaml.getConfigurationSection(path)).orElseGet(() -> Holder.fresh(player));
            Holder changed = change.apply(current);
            after[0] = changed;
            if (changed == null || changed.isBlank()) {
                yaml.set(path, null);
                return;
            }
            yaml.set(path + ".ranks", changed.ranks().stream().sorted().toList());
            yaml.set(path + ".wears", changed.worn().isEmpty() ? null : changed.worn());
            yaml.set(path + ".show.chat", changed.chat());
            yaml.set(path + ".show.nametag", changed.nametag());
            yaml.set(path + ".show.tablist", changed.tablist());
        });
        if (!written) {
            return false;
        }
        if (after[0] == null || after[0].isBlank()) {
            holders.remove(player);
        } else {
            holders.put(player, after[0]);
        }
        return true;
    }
}
