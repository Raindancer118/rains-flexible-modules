package de.raindancer.modules.essentials.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.ui.identity.Nicknames;
import de.raindancer.modules.essentials.model.Nickname;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <h2>On a network</h2>
 * One document for every server ({@code YamlStore.network}): a nickname and who somebody ignores are the
 * player's wherever they play. A change is written as the change it is — added to, or removed from, the
 * document as it is <em>now</em> — never as this server's whole picture, so two servers changing different
 * players, or the same player's ignore list, lose nothing of each other's. {@link #watch()} reloads when
 * another server writes; changes not yet written are kept across the reload, and a read that fails keeps
 * what is held.
 *
 * <p>A nickname is claimed ({@link #claimNickname}) inside one read-and-write of the document that either
 * sees nobody else wearing it or does not write: two servers handing out the same nickname at the same
 * moment cannot both succeed.
 *
 * <h2>Thread safety</h2>
 * The maps are concurrent because a lookup can come from a chat-render call on any thread; writing
 * goes through {@link #flush()}, called after every change.
 */
public final class EssentialsStore {

    private static final LogChannel log = Log.of("essentials");

    /** What claiming a nickname came to. */
    public enum Claim {
        /** Written: nobody else wears it. */
        GRANTED,
        /** Somebody else already wears it, here or on another server. */
        TAKEN,
        /** The document could not be read or written, so nobody could say; nothing was granted. */
        UNAVAILABLE
    }

    /** One change, applied to the document as it is now and to the maps again after a reload. */
    private interface Change {
        void toDocument(YamlConfiguration yaml);

        void toMemory(EssentialsStore into);
    }

    private final YamlStore store;
    private final Map<UUID, String> nicknames = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignores = new ConcurrentHashMap<>();
    /** Made here and not yet written, in order. */
    private final List<Change> pending = new ArrayList<>();
    /** Serialises reading and writing the document, so memory catches up with a write before the next. */
    private final Object io = new Object();

    public EssentialsStore(Path dataFolder) {
        this(YamlStore.network(dataFolder.resolve("essentials.yml")));
    }

    public EssentialsStore(YamlStore store) {
        this.store = store;
    }

    // ---------------------------------------------------------------------------- nicknames

    public Optional<String> nicknameOf(UUID who) {
        return who == null ? Optional.empty() : Optional.ofNullable(nicknames.get(who));
    }

    /** @return whether this changed anything, so a caller need not write when nothing did */
    public boolean setNickname(UUID who, String raw) {
        if (who == null) {
            return false;
        }
        String previous = nicknames.put(who, raw);
        if (raw.equals(previous)) {
            return false;
        }
        record(new SetNickname(who, raw));
        return true;
    }

    public boolean clearNickname(UUID who) {
        if (who == null || nicknames.remove(who) == null) {
            return false;
        }
        record(new ClearNickname(who));
        return true;
    }

    /**
     * Gives {@code who} this nickname if nobody else wears it — on this server or any other — and writes it,
     * in one step against the document as it is now.
     *
     * <p>Two nicknames are the same when they read the same typed with underscores, in any case and whatever
     * the markup: "Star Light", "star_light" and "&lt;gold&gt;STAR LIGHT&lt;/gold&gt;" are one name.
     */
    public Claim claimNickname(UUID who, String raw) {
        if (who == null || raw == null) {
            return Claim.UNAVAILABLE;
        }
        String wanted = typed(raw);
        synchronized (io) {
            List<Change> batch;
            synchronized (this) {
                batch = List.copyOf(pending);
            }
            boolean[] taken = {false};
            boolean written = store.update(yaml -> {
                batch.forEach(change -> change.toDocument(yaml));
                taken[0] = false;
                ConfigurationSection nicks = yaml.getConfigurationSection("nicknames");
                if (nicks != null) {
                    for (String key : nicks.getKeys(false)) {
                        if (!key.equals(who.toString()) && typed(nicks.getString(key, "")).equals(wanted)) {
                            taken[0] = true;
                            return;
                        }
                    }
                }
                yaml.set("nicknames." + who, raw);
            });
            if (!written) {
                return Claim.UNAVAILABLE;
            }
            synchronized (this) {
                pending.subList(0, batch.size()).clear();
            }
            read();
            return taken[0] ? Claim.TAKEN : Claim.GRANTED;
        }
    }

    private static String typed(String raw) {
        return Nicknames.typed(Nickname.of(raw).plain());
    }

    /** Everybody with a nickname, as written — a copy, for syncing it elsewhere. */
    public Map<UUID, String> nicknames() {
        return Map.copyOf(nicknames);
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
        if (!ignores.computeIfAbsent(who, ignore -> ConcurrentHashMap.newKeySet()).add(ignored)) {
            return false;
        }
        record(new Ignore(who, ignored, true));
        return true;
    }

    public boolean stopIgnoring(UUID who, UUID ignored) {
        if (who == null || ignored == null) {
            return false;
        }
        Set<UUID> theirs = ignores.get(who);
        if (theirs == null || !theirs.remove(ignored)) {
            return false;
        }
        record(new Ignore(who, ignored, false));
        return true;
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

    private synchronized void record(Change change) {
        pending.add(change);
    }

    private record SetNickname(UUID who, String raw) implements Change {
        @Override
        public void toDocument(YamlConfiguration yaml) {
            yaml.set("nicknames." + who, raw);
        }

        @Override
        public void toMemory(EssentialsStore into) {
            into.nicknames.put(who, raw);
        }
    }

    private record ClearNickname(UUID who) implements Change {
        @Override
        public void toDocument(YamlConfiguration yaml) {
            yaml.set("nicknames." + who, null);
        }

        @Override
        public void toMemory(EssentialsStore into) {
            into.nicknames.remove(who);
        }
    }

    private record Ignore(UUID who, UUID ignored, boolean on) implements Change {
        @Override
        public void toDocument(YamlConfiguration yaml) {
            Set<String> ids = new java.util.LinkedHashSet<>(yaml.getStringList("ignores." + who));
            if (on) {
                ids.add(ignored.toString());
            } else {
                ids.remove(ignored.toString());
            }
            yaml.set("ignores." + who, ids.isEmpty() ? null : new ArrayList<>(ids));
        }

        @Override
        public void toMemory(EssentialsStore into) {
            if (on) {
                into.ignores.computeIfAbsent(who, id -> ConcurrentHashMap.newKeySet()).add(ignored);
            } else {
                Set<UUID> theirs = into.ignores.get(who);
                if (theirs != null) {
                    theirs.remove(ignored);
                }
            }
        }
    }

    /**
     * Reads the document. Called when the module starts and when another server wrote it.
     *
     * @return false when it could not be read — what is held stays
     */
    public boolean load() {
        synchronized (io) {
            return read();
        }
    }

    private boolean read() {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            for (String problem : store.problems()) {
                log.warn("essentials.yml: {} — keeping the nicknames and ignores held in memory.", problem);
            }
            return false;
        }
        YamlConfiguration yaml = read.get();
        Map<UUID, String> freshNicks = new HashMap<>();
        Map<UUID, Set<UUID>> freshIgnores = new HashMap<>();

        ConfigurationSection nicks = yaml.getConfigurationSection("nicknames");
        if (nicks != null) {
            for (String key : nicks.getKeys(false)) {
                asUuid(key).ifPresent(id -> freshNicks.put(id, nicks.getString(key, "")));
            }
        }
        ConfigurationSection ignoring = yaml.getConfigurationSection("ignores");
        if (ignoring != null) {
            for (String key : ignoring.getKeys(false)) {
                asUuid(key).ifPresent(id -> {
                    Set<UUID> theirs = new HashSet<>();
                    for (String ignored : ignoring.getStringList(key)) {
                        asUuid(ignored).ifPresent(theirs::add);
                    }
                    if (!theirs.isEmpty()) {
                        freshIgnores.put(id, theirs);
                    }
                });
            }
        }
        synchronized (this) {
            // Replaced without ever being empty: a chat line drawn in between must not lose a nickname.
            nicknames.keySet().retainAll(freshNicks.keySet());
            nicknames.putAll(freshNicks);
            ignores.keySet().retainAll(freshIgnores.keySet());
            freshIgnores.forEach((id, theirs) -> {
                Set<UUID> held = ignores.computeIfAbsent(id, who -> ConcurrentHashMap.newKeySet());
                held.retainAll(theirs);
                held.addAll(theirs);
            });
            pending.forEach(change -> change.toMemory(this));
        }
        return true;
    }

    /** Writes what changed here, onto the document as it is now. @return whether it reached the database */
    public boolean flush() {
        synchronized (io) {
            return write();
        }
    }

    private boolean write() {
        List<Change> batch;
        synchronized (this) {
            if (pending.isEmpty()) {
                return true;
            }
            batch = List.copyOf(pending);
        }
        if (!store.update(yaml -> batch.forEach(change -> change.toDocument(yaml)))) {
            return false;
        }
        synchronized (this) {
            pending.subList(0, batch.size()).clear();
        }
        // What other servers wrote is in the document now; memory takes it before anything else is said.
        read();
        return true;
    }

    /** Reloads when another server writes the document, after writing what this one changed. Close to stop. */
    public NetworkBus.Subscription watch() {
        return watch(() -> { });
    }

    /** As {@link #watch()}, and says so after each reload — for redrawing a nickname somebody else just changed. */
    public NetworkBus.Subscription watch(Runnable afterReload) {
        return store.onChange(() -> {
            synchronized (io) {
                write();
                read();
            }
            afterReload.run();
        });
    }

    private static Optional<UUID> asUuid(String text) {
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }
}
