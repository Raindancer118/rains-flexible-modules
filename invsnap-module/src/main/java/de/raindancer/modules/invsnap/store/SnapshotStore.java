package de.raindancer.modules.invsnap.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.invsnap.model.Snapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One file per player, holding that player's whole snapshot history — the same shape {@code
 * mannequin-module}'s {@code MannequinStore} uses for one file per mannequin, rather than a single
 * shared file every snapshot on the server would have to be read and rewritten through.
 */
public final class SnapshotStore {

    private final Path folder;

    public SnapshotStore(Path dataFolder) {
        this.folder = dataFolder.resolve("snapshots");
        try {
            Files.createDirectories(folder);
        } catch (IOException failure) {
            throw new UncheckedIOException("could not create " + folder, failure);
        }
    }

    private YamlStore storeFor(UUID playerId) {
        return new YamlStore(folder.resolve(playerId + ".yml"));
    }

    /** Every player id this store has a file for — the whole roster the root screen picks from. */
    public List<UUID> knownPlayerIds() {
        try (var files = Files.list(folder)) {
            List<UUID> ids = new ArrayList<>();
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".yml")) {
                    continue;
                }
                try {
                    ids.add(UUID.fromString(name.substring(0, name.length() - ".yml".length())));
                } catch (IllegalArgumentException notAUuid) {
                    // Not one of ours — skip rather than fail the whole roster over a stray file.
                }
            }
            return ids;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("could not list " + folder, unreadable);
        }
    }

    /** A player's whole stored history, oldest first. Nothing on disk is an empty list, not an error. */
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
