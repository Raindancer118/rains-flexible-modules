package de.raindancer.modules.manhunt.service;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The handful of people the server's door is never shut on: never swept up by
 * {@link ManhuntWhitelistService#clear()}, and whitelisted by {@link ManhuntWhitelistService#close()}
 * whether or not they happened to be online when it closed.
 *
 * <h2>Why a file of its own rather than a settings field</h2>
 * {@code ManhuntSettings} is a record of scalars rendered in a GUI — a growing list of player ids is
 * neither, and encoding one as a comma-joined string would give an owner a line nobody can hand-edit
 * and a screen nobody can use. This is {@code YamlStore}'s own job, and it is the one thing this
 * module keeps on disk.
 *
 * <h2>Why the name is stored beside the id</h2>
 * The id is the truth — a rename must not lose somebody their place — but nobody can read a list of
 * UUIDs, and {@code /whitelist vip remove} has to work for somebody who is offline and not in the
 * server's own cache, which is exactly when Bukkit cannot turn a name back into an id. The name is
 * refreshed on every add, so a renamed VIP is listed under whatever they wear now.
 */
public final class WhitelistVips {

    private static final LogChannel log = Log.of("manhunt");

    /** The section every entry lives under, so the file has room for something else one day. */
    private static final String SECTION = "vips";

    private final YamlStore store;
    /** id → last known name. Thread-safe: a command and a hunt ending may both reach this. */
    private final Map<UUID, String> vips = new ConcurrentHashMap<>();

    public WhitelistVips(Path file) {
        this.store = new YamlStore(file);
        load();
    }

    private void load() {
        if (!store.exists()) {
            return;
        }
        YamlConfiguration yaml = store.read();
        ConfigurationSection section = yaml.getConfigurationSection(SECTION);
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                vips.put(UUID.fromString(key), section.getString(key, key));
            } catch (IllegalArgumentException notAnId) {
                log.warn("'{}' in {} is not a player id; that VIP entry was skipped.", key, store.file());
            }
        }
    }

    private void save() {
        Map<UUID, String> snapshot = new LinkedHashMap<>(vips);
        store.write(yaml -> {
            yaml.set(SECTION, null);
            for (Map.Entry<UUID, String> vip : snapshot.entrySet()) {
                yaml.set(SECTION + "." + vip.getKey(), vip.getValue());
            }
        });
    }

    /**
     * Adds {@code id}, or refreshes the name of somebody already on the list.
     *
     * @return whether this actually added somebody new — so a command can say "already a VIP"
     */
    public boolean add(UUID id, String name) {
        if (id == null) {
            return false;
        }
        String previous = vips.put(id, name == null || name.isBlank() ? id.toString() : name);
        save();
        return previous == null;
    }

    /** @return whether they were on the list at all */
    public boolean remove(UUID id) {
        if (id == null || vips.remove(id) == null) {
            return false;
        }
        save();
        return true;
    }

    public boolean isVip(UUID id) {
        return id != null && vips.containsKey(id);
    }

    /** Everybody on the list, by id. */
    public Set<UUID> ids() {
        return Set.copyOf(vips.keySet());
    }

    /** Everybody on the list, by the name they were last seen under — for a listing, and nothing else. */
    public List<String> names() {
        return vips.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /**
     * The id filed under {@code name}, case-insensitively — this module's own answer to a name, for
     * a VIP the server itself can no longer resolve because they have not been seen in a long time.
     */
    public Optional<UUID> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        return vips.entrySet().stream()
                .filter(vip -> vip.getValue().toLowerCase(Locale.ROOT).equals(wanted))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public int size() {
        return vips.size();
    }
}
