package de.raindancer.modules.anticheat.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.anticheat.model.CheckType;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Which checks a moderator does not want to hear about in chat, and which nobody does — kept in
 * {@code alert-mutes.yml}. Only chat is quietened: evidence, replays and staff reports go on as before.
 */
public final class AlertMutes {

    private final YamlStore store;
    private final Map<UUID, Set<CheckType>> personal = new ConcurrentHashMap<>();
    private final Set<CheckType> everyone = java.util.Collections.synchronizedSet(EnumSet.noneOf(CheckType.class));
    private final AtomicBoolean dirty = new AtomicBoolean();

    public AlertMutes(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("alert-mutes.yml"));
    }

    /** @return whether it was not muted for them before */
    public boolean mute(UUID staff, CheckType check) {
        boolean changed = personal.computeIfAbsent(staff, ignored -> java.util.Collections.synchronizedSet(EnumSet.noneOf(CheckType.class)))
                .add(check);
        dirty.set(true);
        return changed;
    }

    /** @return whether it was muted for them before */
    public boolean unmute(UUID staff, CheckType check) {
        Set<CheckType> theirs = personal.get(staff);
        boolean changed = theirs != null && theirs.remove(check);
        dirty.set(true);
        return changed;
    }

    public boolean muteForEveryone(CheckType check) {
        dirty.set(true);
        return everyone.add(check);
    }

    public boolean unmuteForEveryone(CheckType check) {
        dirty.set(true);
        return everyone.remove(check);
    }

    public boolean isMuted(UUID staff, CheckType check) {
        if (everyone.contains(check)) {
            return true;
        }
        Set<CheckType> theirs = personal.get(staff);
        return theirs != null && theirs.contains(check);
    }

    /** What of a gathered line this moderator still wants: the muted checks taken out. */
    public Map<CheckType, Integer> visible(UUID staff, Map<CheckType, Integer> counts) {
        Map<CheckType, Integer> kept = new EnumMap<>(CheckType.class);
        counts.forEach((check, count) -> {
            if (!isMuted(staff, check)) {
                kept.put(check, count);
            }
        });
        return kept;
    }

    public Set<CheckType> mutedBy(UUID staff) {
        Set<CheckType> theirs = personal.get(staff);
        if (theirs == null) {
            return Set.of();
        }
        synchronized (theirs) {
            return theirs.isEmpty() ? Set.of() : EnumSet.copyOf(theirs);
        }
    }

    public Set<CheckType> mutedForEveryone() {
        synchronized (everyone) {
            return everyone.isEmpty() ? Set.of() : EnumSet.copyOf(everyone);
        }
    }

    public void load() {
        var yaml = store.read();
        everyone.clear();
        for (String key : yaml.getStringList("everyone")) {
            CheckType.find(key).ifPresent(everyone::add);
        }
        personal.clear();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String id : players.getKeys(false)) {
            try {
                UUID staff = UUID.fromString(id);
                for (String key : players.getStringList(id)) {
                    CheckType.find(key).ifPresent(check -> mute(staff, check));
                }
            } catch (IllegalArgumentException notAnId) {
                // A hand-edited line that is no player: skipped, the rest still load.
            }
        }
        dirty.set(false);
    }

    /** @return whether it reached the disk; nothing to write counts as reached */
    public boolean flush() {
        if (!dirty.getAndSet(false)) {
            return true;
        }
        boolean written = store.write(yaml -> {
            yaml.set("everyone", mutedForEveryone().stream().map(CheckType::key).sorted().toList());
            personal.forEach((staff, checks) -> {
                Set<CheckType> copy = mutedBy(staff);
                if (!copy.isEmpty()) {
                    yaml.set("players." + staff, copy.stream().map(CheckType::key).sorted().toList());
                }
            });
        });
        if (!written) {
            dirty.set(true);
        }
        return written;
    }
}
