package de.raindancer.modules.essentials.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nicknames and ignore lists — the two things this module keeps that outlive a restart.
 *
 * <h2>Why one file for two unrelated things</h2>
 * Because both are small, both are read constantly and written rarely, and a player's data folder
 * accumulating one file per feature is not a virtue. Core's own {@code YamlStore} is what makes this
 * safe: a server killed mid-write has the old file or the new one, never half of each.
 *
 * <h2>Thread safety</h2>
 * The maps are concurrent because a lookup can come from a chat-render call on any thread; writing
 * goes through {@link #flush()}, called after every change, the same way {@code ImmuneStaff} does it.
 */
public final class EssentialsStore {

    private static final LogChannel log = Log.of("essentials");

    private final YamlStore store;
    private final Map<UUID, String> nicknames = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignores = new ConcurrentHashMap<>();

    public EssentialsStore(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("essentials.yml"));
    }

    // ---------------------------------------------------------------------------- nicknames

    public java.util.Optional<String> nicknameOf(UUID who) {
        return who == null ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(nicknames.get(who));
    }

    /** @return whether this changed anything, so a caller need not write when nothing did */
    public boolean setNickname(UUID who, String raw) {
        if (who == null) {
            return false;
        }
        String previous = nicknames.put(who, raw);
        return !raw.equals(previous);
    }

    public boolean clearNickname(UUID who) {
        return who != null && nicknames.remove(who) != null;
    }

    public int nicknameCount() {
        return nicknames.size();
    }

    // ---------------------------------------------------------------------------- ignoring

    /** @return whether this changed anything */
    public boolean ignore(UUID who, UUID ignored) {
        if (who == null || ignored == null || who.equals(ignored)) {
            return false;
        }
        return ignores.computeIfAbsent(who, ignore -> ConcurrentHashMap.newKeySet()).add(ignored);
    }

    public boolean stopIgnoring(UUID who, UUID ignored) {
        if (who == null || ignored == null) {
            return false;
        }
        Set<UUID> theirs = ignores.get(who);
        return theirs != null && theirs.remove(ignored);
    }

    public boolean isIgnoring(UUID who, UUID other) {
        if (who == null || other == null) {
            return false;
        }
        Set<UUID> theirs = ignores.get(who);
        return theirs != null && theirs.contains(other);
    }

    public Set<UUID> ignoredBy(UUID who) {
        Set<UUID> theirs = who == null ? null : ignores.get(who);
        return theirs == null ? Set.of() : Set.copyOf(theirs);
    }

    // ---------------------------------------------------------------------------- persistence

    /** Reads what is on disk. Called once, when the module starts. */
    public void load() {
        org.bukkit.configuration.file.YamlConfiguration yaml = store.read();
        nicknames.clear();
        ignores.clear();

        ConfigurationSection nicks = yaml.getConfigurationSection("nicknames");
        if (nicks != null) {
            for (String key : nicks.getKeys(false)) {
                asUuid(key).ifPresent(id -> nicknames.put(id, nicks.getString(key, "")));
            }
        }

        ConfigurationSection ignoring = yaml.getConfigurationSection("ignores");
        if (ignoring != null) {
            for (String key : ignoring.getKeys(false)) {
                asUuid(key).ifPresent(id -> {
                    Set<UUID> theirs = ConcurrentHashMap.newKeySet();
                    for (String ignored : ignoring.getStringList(key)) {
                        asUuid(ignored).ifPresent(theirs::add);
                    }
                    if (!theirs.isEmpty()) {
                        ignores.put(id, theirs);
                    }
                });
            }
        }
        for (String problem : store.problems()) {
            log.warn("essentials.yml: {}", problem);
        }
    }

    /** Writes the lot. @return whether it reached the disk */
    public boolean flush() {
        return store.write(yaml -> {
            for (Map.Entry<UUID, String> entry : nicknames.entrySet()) {
                yaml.set("nicknames." + entry.getKey(), entry.getValue());
            }
            for (Map.Entry<UUID, Set<UUID>> entry : ignores.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                List<String> ids = new ArrayList<>();
                entry.getValue().forEach(id -> ids.add(id.toString()));
                yaml.set("ignores." + entry.getKey(), ids);
            }
        });
    }

    private static java.util.Optional<UUID> asUuid(String text) {
        try {
            return java.util.Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException notAnId) {
            return java.util.Optional.empty();
        }
    }
}
