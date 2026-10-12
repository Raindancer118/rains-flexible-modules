package de.raindancer.modules.ranks.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.ranks.model.Holder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Who holds which rank and how they want it shown, in {@code holders.yml}. Read on every chat line and every
 * tablist and nametag pass, so held in memory and readable from any thread; written whole on a change, which is
 * rare.
 *
 * <p>A file that could not be read is never written over: until somebody fixes it nothing can change, because
 * saving would replace everybody's ranks with only the new one.
 */
public final class HolderBook {

    private final YamlStore store;
    private final Map<UUID, Holder> holders = new ConcurrentHashMap<>();
    private volatile boolean readable = true;

    public HolderBook(YamlStore store) {
        this.store = store;
    }

    public synchronized void load() {
        holders.clear();
        YamlConfiguration yaml = store.read();
        readable = store.problems().isEmpty();
        ConfigurationSection all = yaml.getConfigurationSection("holders");
        if (all == null) {
            return;
        }
        for (String key : all.getKeys(false)) {
            ConfigurationSection each = all.getConfigurationSection(key);
            if (each == null) {
                continue;
            }
            try {
                UUID player = UUID.fromString(key);
                Holder holder = new Holder(player, new LinkedHashSet<>(each.getStringList("ranks")),
                        each.getString("wears", ""), each.getBoolean("show.chat", true),
                        each.getBoolean("show.nametag", true), each.getBoolean("show.tablist", true));
                if (!holder.isBlank()) {
                    holders.put(player, holder);
                }
            } catch (IllegalArgumentException notAPlayer) {
                // Skipped; the rest still count.
            }
        }
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
        if (!readable || player == null) {
            return false;
        }
        Holder before = holders.get(player);
        Holder after = change.apply(before != null ? before : Holder.fresh(player));
        put(player, after);
        if (save()) {
            return true;
        }
        put(player, before);
        return false;
    }

    private void put(UUID player, Holder holder) {
        if (holder == null || holder.isBlank()) {
            holders.remove(player);
        } else {
            holders.put(player, holder);
        }
    }

    private boolean save() {
        Map<UUID, Holder> snapshot = new HashMap<>(holders);
        return store.write(yaml -> snapshot.values().forEach(holder -> {
            String path = "holders." + holder.player();
            yaml.set(path + ".ranks", holder.ranks().stream().sorted().toList());
            if (!holder.worn().isEmpty()) {
                yaml.set(path + ".wears", holder.worn());
            }
            yaml.set(path + ".show.chat", holder.chat());
            yaml.set(path + ".show.nametag", holder.nametag());
            yaml.set(path + ".show.tablist", holder.tablist());
        }));
    }
}
