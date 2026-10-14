package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.moderation.model.CheckType;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.List;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Which checks a moderator does not want to hear about in chat, and which nobody does — kept in
 * {@code alert-mutes.yml}, a document of the network: a moderator's mutes follow them from server to server, and
 * a check muted for everybody is muted on all of them. Only chat is quietened: evidence, replays and staff
 * reports go on as before.
 *
 * <p>A flush writes only what this server changed since the last one, so two servers muting different things
 * do not undo each other; when another server writes, {@link #connect} reads it again.
 */
public final class AlertMutes {

    private final YamlStore store;
    private final Map<UUID, Set<CheckType>> personal = new ConcurrentHashMap<>();
    private final Set<CheckType> everyone = java.util.Collections.synchronizedSet(EnumSet.noneOf(CheckType.class));
    private final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dirtyEveryone = new AtomicBoolean();

    public AlertMutes(Path dataFolder) {
        this(YamlStore.network(dataFolder.resolve("alert-mutes.yml")));
    }

    public AlertMutes(YamlStore store) {
        this.store = store;
    }

    /** @return whether it was not muted for them before */
    public boolean mute(UUID staff, CheckType check) {
        boolean changed = personal.computeIfAbsent(staff, ignored -> java.util.Collections.synchronizedSet(EnumSet.noneOf(CheckType.class)))
                .add(check);
        dirtyPlayers.add(staff);
        return changed;
    }

    /** @return whether it was muted for them before */
    public boolean unmute(UUID staff, CheckType check) {
        Set<CheckType> theirs = personal.get(staff);
        boolean changed = theirs != null && theirs.remove(check);
        dirtyPlayers.add(staff);
        return changed;
    }

    public boolean muteForEveryone(CheckType check) {
        dirtyEveryone.set(true);
        return everyone.add(check);
    }

    public boolean unmuteForEveryone(CheckType check) {
        dirtyEveryone.set(true);
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

    /** Reads another server's change in, after writing what this server changed so it is not lost. */
    public AutoCloseable connect() {
        var subscription = store.onChange(() -> {
            flush();
            load();
        });
        return subscription::close;
    }

    public void load() {
        var read = store.readIfReadable();
        if (read.isEmpty()) {
            return;
        }
        var yaml = read.get();
        Set<CheckType> allMuted = EnumSet.noneOf(CheckType.class);
        for (String key : yaml.getStringList("everyone")) {
            CheckType.find(key).ifPresent(allMuted::add);
        }
        Map<UUID, Set<CheckType>> theirs = new java.util.HashMap<>();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String id : players.getKeys(false)) {
                try {
                    UUID staff = UUID.fromString(id);
                    Set<CheckType> checks = EnumSet.noneOf(CheckType.class);
                    for (String key : players.getStringList(id)) {
                        CheckType.find(key).ifPresent(checks::add);
                    }
                    theirs.put(staff, checks);
                } catch (IllegalArgumentException notAnId) {
                    // A hand-edited line that is no player: skipped, the rest still load.
                }
            }
        }
        synchronized (everyone) {
            if (!dirtyEveryone.get()) {
                everyone.clear();
                everyone.addAll(allMuted);
            }
        }
        // What this server changed and has not written yet stays as it is.
        theirs.forEach((staff, checks) -> {
            if (!dirtyPlayers.contains(staff)) {
                personal.put(staff, java.util.Collections.synchronizedSet(checks));
            }
        });
        personal.keySet().removeIf(staff -> !theirs.containsKey(staff) && !dirtyPlayers.contains(staff));
    }

    /** @return whether it reached the database; nothing to write counts as reached */
    public synchronized boolean flush() {
        boolean allChanged = dirtyEveryone.getAndSet(false);
        Set<UUID> changed = new java.util.HashSet<>(dirtyPlayers);
        dirtyPlayers.removeAll(changed);
        if (!allChanged && changed.isEmpty()) {
            return true;
        }
        List<String> all = mutedForEveryone().stream().map(CheckType::key).sorted().toList();
        Map<UUID, List<String>> theirs = new java.util.HashMap<>();
        changed.forEach(staff -> theirs.put(staff, mutedBy(staff).stream().map(CheckType::key).sorted().toList()));
        boolean written = store.update(yaml -> {
            if (allChanged) {
                yaml.set("everyone", all);
            }
            theirs.forEach((staff, checks) -> yaml.set("players." + staff, checks.isEmpty() ? null : checks));
        });
        if (!written) {
            dirtyEveryone.set(dirtyEveryone.get() || allChanged);
            dirtyPlayers.addAll(changed);
        }
        return written;
    }
}
