package de.raindancer.modules.invsnap.store;

import de.raindancer.core.data.store.DocumentFolder;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.invsnap.model.Snapshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One document per player, holding that player's whole snapshot history — the same shape {@code
 * mannequin-module}'s {@code MannequinStore} uses for one file per mannequin, rather than a single
 * shared file every snapshot on the server would have to be read and rewritten through.
 */
public final class SnapshotStore {

    private static final String SUFFIX = ".yml";

    private final DocumentFolder folder;

    public SnapshotStore(Path dataFolder) {
        // Inventories stay per server, so these are this server's documents.
        this(DocumentFolder.of(dataFolder.resolve("snapshots")));
    }

    public SnapshotStore(DocumentFolder folder) {
        this.folder = folder;
    }

    private YamlStore storeFor(UUID playerId) {
        return folder.store(playerId + SUFFIX);
    }

    /** Every player id this store has a document for — the whole roster the root screen picks from. */
    public List<UUID> knownPlayerIds() {
        List<UUID> ids = new ArrayList<>();
        for (String name : folder.names(SUFFIX)) {
            try {
                ids.add(UUID.fromString(name.substring(0, name.length() - SUFFIX.length())));
            } catch (IllegalArgumentException notAUuid) {
                // Not one of ours — skip rather than fail the whole roster over a stray entry.
            }
        }
        return ids;
    }

    /**
     * Drops every snapshot taken before {@code cutoff}, for every player, and the document of a player left
     * with none. Players who never come back would otherwise keep their last window for ever.
     *
     * @return how many snapshots were dropped
     */
    public int pruneOlderThan(Instant cutoff) {
        int dropped = 0;
        for (UUID playerId : knownPlayerIds()) {
            List<Snapshot> all = load(playerId);
            List<Snapshot> kept = all.stream().filter(snapshot -> !snapshot.takenAt().isBefore(cutoff)).toList();
            if (kept.size() == all.size()) {
                continue;
            }
            boolean done = kept.isEmpty() ? folder.delete(playerId + SUFFIX) : saveAll(playerId, kept);
            if (done) {
                dropped += all.size() - kept.size();
            }
        }
        return dropped;
    }

    /** A player's whole stored history, oldest first. Nothing stored is an empty list, not an error. */
    public List<Snapshot> load(UUID playerId) {
        List<Map<?, ?>> raw = storeFor(playerId).read().getMapList("snapshots");
        List<Snapshot> found = new ArrayList<>();
        for (Map<?, ?> entry : raw) {
            Snapshot snapshot = fromMap(playerId, entry);
            if (snapshot != null) {
                found.add(snapshot);
            }
        }
        return found;
    }

    /** Replaces this player's whole stored history with exactly the given list. */
    public boolean saveAll(UUID playerId, List<Snapshot> snapshots) {
        List<Map<String, Object>> asMaps = new ArrayList<>();
        for (Snapshot snapshot : snapshots) {
            asMaps.add(toMap(snapshot));
        }
        return storeFor(playerId).update(yaml -> yaml.set("snapshots", asMaps));
    }

    private static Map<String, Object> toMap(Snapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("player-name", snapshot.playerName());
        map.put("taken-at", snapshot.takenAt().toEpochMilli());
        map.put("main", snapshot.mainInventory());
        map.put("armor", snapshot.armor());
        map.put("offhand", snapshot.offHand());
        return map;
    }

    private static Snapshot fromMap(UUID playerId, Map<?, ?> raw) {
        Object takenAtRaw = raw.get("taken-at");
        if (!(takenAtRaw instanceof Number takenAtMillis)) {
            return null;
        }
        Instant takenAt = Instant.ofEpochMilli(takenAtMillis.longValue());
        String name = stringOf(raw.get("player-name"), playerId.toString());
        List<String> main = stringListOf(raw.get("main"));
        List<String> armor = stringListOf(raw.get("armor"));
        String offHand = stringOf(raw.get("offhand"), Snapshot.EMPTY_SLOT);
        return new Snapshot(playerId, name, takenAt, main, armor, offHand);
    }

    private static String stringOf(Object value, String whenAbsent) {
        return value == null ? whenAbsent : String.valueOf(value);
    }

    private static List<String> stringListOf(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object element : list) {
            strings.add(element == null ? Snapshot.EMPTY_SLOT : String.valueOf(element));
        }
        return strings;
    }
}
