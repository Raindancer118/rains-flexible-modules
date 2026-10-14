package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.moderation.model.Evidence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every failed check, per player, newest kept — for the whole network, so staff read a player's record on any
 * server. The database is the truth; this keeps a copy in memory, gathers new evidence and writes it as inserts
 * ({@link #flush}), and reads a player again when another server says it wrote something for them.
 */
public final class EvidenceLog {

    public static final String TOPIC = "anticheat-evidence";

    private static final LogChannel log = Log.of("anticheat");

    private final Database database;
    private final NetworkBus bus;
    private final Map<UUID, Deque<Evidence>> entries = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** When the anti-cheat started measuring a player afresh: what came before is history, not a measure. */
    private final Map<UUID, Long> measuredFrom = new ConcurrentHashMap<>();
    private volatile int capacity;
    private final java.util.concurrent.atomic.AtomicLong flushes = new java.util.concurrent.atomic.AtomicLong();

    private final Object lock = new Object();
    /** What this server changed and the database has not got yet; kept over every re-read. */
    private final Map<UUID, List<Evidence>> newEvidence = new HashMap<>();
    private final Map<UUID, Long> newMeasures = new HashMap<>();
    private final Set<UUID> wiped = new HashSet<>();
    private final Set<UUID> renamed = new HashSet<>();
    /** The batch being written right now: a re-read in the meantime must not lose it. */
    private Batch inFlight = new Batch(Map.of(), Map.of(), Set.of());

    private record Batch(Map<UUID, List<Evidence>> adds, Map<UUID, Long> measures, Set<UUID> clears) {
    }

    public EvidenceLog(Database database, NetworkBus bus, int capacity) {
        this.database = database;
        this.bus = bus == null ? NetworkBus.NONE : bus;
        this.capacity = Math.max(1, capacity);
    }

    /** Hears what other servers wrote, and reads everything again when this server may have missed some. */
    public AutoCloseable connect(NetworkBus network) {
        NetworkBus.Subscription changes = network.subscribe(TOPIC, event -> {
            try {
                reload(UUID.fromString(event.key()));
            } catch (IllegalArgumentException everything) {
                load();
            }
        });
        NetworkBus.Subscription missed = network.onResync(this::load);
        return () -> {
            changes.close();
            missed.close();
        };
    }

    public void capacity(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public void add(UUID who, String name, Evidence evidence) {
        Deque<Evidence> theirs = entries.computeIfAbsent(who, ignored -> new ArrayDeque<>());
        synchronized (theirs) {
            theirs.addLast(evidence);
            while (theirs.size() > capacity) {
                theirs.removeFirst();
            }
        }
        synchronized (lock) {
            newEvidence.computeIfAbsent(who, ignored -> new ArrayList<>()).add(evidence);
            if (name != null && !name.equals(names.put(who, name))) {
                renamed.add(who);
            }
        }
    }

    /** Newest first. */
    public List<Evidence> of(UUID who) {
        Deque<Evidence> theirs = entries.get(who);
        if (theirs == null) {
            return List.of();
        }
        synchronized (theirs) {
            List<Evidence> copy = new ArrayList<>(theirs);
            java.util.Collections.reverse(copy);
            return copy;
        }
    }

    /** Newest first, only what came after the last fresh start — what a measure of them is built on. */
    public List<Evidence> since(UUID who) {
        long from = measuredFrom(who);
        return of(who).stream().filter(entry -> entry.atMillis() >= from).toList();
    }

    /** From now on the evidence before {@code millis} is history: kept, shown, never counted. */
    public void measureFrom(UUID who, long millis) {
        measuredFrom.put(who, millis);
        synchronized (lock) {
            newMeasures.put(who, millis);
        }
    }

    /** 0 when they were never started afresh. */
    public long measuredFrom(UUID who) {
        return measuredFrom.getOrDefault(who, 0L);
    }

    public Set<UUID> everybody() {
        return Set.copyOf(entries.keySet());
    }

    public String nameOf(UUID who) {
        return names.get(who);
    }

    public void clear(UUID who) {
        entries.remove(who);
        measuredFrom.remove(who);
        synchronized (lock) {
            newEvidence.remove(who);
            newMeasures.remove(who);
            wiped.add(who);
        }
    }

    /** Reads every player's evidence from the database; if it cannot be read, what is in memory stays. */
    public void load() {
        read(null);
    }

    private void reload(UUID who) {
        read(who);
    }

    private void read(UUID only) {
        // A flush that finishes while this reads may have changed what it read; reading again is cheap.
        for (int attempt = 0; attempt < 3; attempt++) {
            long before = flushes.get();
            Optional<Loaded> loaded = query(only);
            if (loaded.isEmpty()) {
                return;
            }
            synchronized (lock) {
                if (flushes.get() == before || attempt == 2) {
                    apply(loaded.get(), only);
                    return;
                }
            }
        }
    }

    private Optional<Loaded> query(UUID only) {
        return database.read(connection -> {
            Loaded found = new Loaded();
            String where = only == null ? "" : " WHERE player = ?";
            try (PreparedStatement evidence = connection.prepareStatement(
                    "SELECT player, at, check_key, level, detail, world, x, y, z, ping, tps FROM ac_evidence"
                            + where + " ORDER BY at, id");
                 PreparedStatement players = connection.prepareStatement(
                         "SELECT player, name, measured_from FROM ac_player" + where)) {
                if (only != null) {
                    evidence.setString(1, only.toString());
                    players.setString(1, only.toString());
                }
                try (ResultSet rows = evidence.executeQuery()) {
                    while (rows.next()) {
                        UUID who = idOf(rows.getString(1));
                        if (who != null) {
                            found.evidence.computeIfAbsent(who, ignored -> new ArrayDeque<>()).addLast(new Evidence(
                                    rows.getLong(2), rows.getString(3), rows.getDouble(4), rows.getString(5),
                                    rows.getString(6), rows.getInt(7), rows.getInt(8), rows.getInt(9), rows.getInt(10),
                                    rows.getDouble(11)));
                        }
                    }
                }
                try (ResultSet rows = players.executeQuery()) {
                    while (rows.next()) {
                        UUID who = idOf(rows.getString(1));
                        if (who != null) {
                            found.names.put(who, rows.getString(2));
                            found.measured.put(who, rows.getLong(3));
                        }
                    }
                }
            }
            return found;
        });
    }

    private void apply(Loaded found, UUID only) {
        synchronized (lock) {
            Set<UUID> concerned = new HashSet<>(found.evidence.keySet());
            concerned.addAll(found.measured.keySet());
            concerned.addAll(newEvidence.keySet());
            concerned.addAll(inFlight.adds().keySet());
            if (only == null) {
                concerned.addAll(entries.keySet());
                concerned.addAll(measuredFrom.keySet());
            } else {
                concerned.retainAll(Set.of(only));
                concerned.add(only);
            }
            for (UUID who : concerned) {
                boolean clearing = wiped.contains(who) || inFlight.clears().contains(who);
                Deque<Evidence> theirs = clearing ? new ArrayDeque<>()
                        : found.evidence.getOrDefault(who, new ArrayDeque<>());
                List<Evidence> all = new ArrayList<>(theirs);
                // Already in the rows when a write has just landed but not yet been cleared from the batch.
                for (Evidence waiting : inFlight.adds().getOrDefault(who, List.of())) {
                    if (!all.contains(waiting)) {
                        all.add(waiting);
                    }
                }
                all.addAll(newEvidence.getOrDefault(who, List.of()));
                all.sort(java.util.Comparator.comparingLong(Evidence::atMillis));
                theirs = new ArrayDeque<>(all);
                while (theirs.size() > capacity) {
                    theirs.removeFirst();
                }
                if (theirs.isEmpty()) {
                    entries.remove(who);
                } else {
                    entries.put(who, theirs);
                }
                Long measured = newMeasures.getOrDefault(who, inFlight.measures().get(who));
                if (measured == null && !clearing) {
                    long saved = found.measured.getOrDefault(who, 0L);
                    measured = saved > 0 ? saved : null;
                }
                if (measured == null) {
                    measuredFrom.remove(who);
                } else {
                    measuredFrom.put(who, measured);
                }
                String name = found.names.get(who);
                if (name != null && !name.isEmpty() && !renamed.contains(who)) {
                    names.put(who, name);
                }
            }
        }
    }

    /** @return whether everything new reached the database; what did not is kept for the next try */
    public boolean flush() {
        Map<UUID, List<Evidence>> adds;
        Map<UUID, Long> measures;
        Set<UUID> clears;
        Map<UUID, String> named = new HashMap<>();
        synchronized (lock) {
            if (newEvidence.isEmpty() && newMeasures.isEmpty() && wiped.isEmpty() && renamed.isEmpty()) {
                return true;
            }
            adds = new HashMap<>(newEvidence);
            measures = new HashMap<>(newMeasures);
            clears = new HashSet<>(wiped);
            renamed.forEach(who -> named.put(who, names.getOrDefault(who, "")));
            newEvidence.clear();
            newMeasures.clear();
            wiped.clear();
            renamed.clear();
            inFlight = new Batch(adds, measures, clears);
        }
        int keep = capacity;
        boolean written = database.write(connection -> {
            try (PreparedStatement dropEvidence = connection.prepareStatement("DELETE FROM ac_evidence WHERE player = ?");
                 PreparedStatement dropPlayer = connection.prepareStatement("DELETE FROM ac_player WHERE player = ?");
                 PreparedStatement name = connection.prepareStatement(
                         "INSERT INTO ac_player (player, name, measured_from) VALUES (?, ?, 0) "
                                 + "ON CONFLICT(player) DO UPDATE SET name = excluded.name");
                 PreparedStatement measure = connection.prepareStatement(
                         "INSERT INTO ac_player (player, name, measured_from) VALUES (?, ?, ?) "
                                 + "ON CONFLICT(player) DO UPDATE SET measured_from = excluded.measured_from");
                 PreparedStatement insert = connection.prepareStatement(INSERT);
                 PreparedStatement prune = connection.prepareStatement(
                         "DELETE FROM ac_evidence WHERE player = ? AND id NOT IN "
                                 + "(SELECT id FROM ac_evidence WHERE player = ? ORDER BY at DESC, id DESC LIMIT ?)")) {
                for (UUID who : clears) {
                    dropEvidence.setString(1, who.toString());
                    dropEvidence.executeUpdate();
                    dropPlayer.setString(1, who.toString());
                    dropPlayer.executeUpdate();
                }
                for (Map.Entry<UUID, String> each : named.entrySet()) {
                    name.setString(1, each.getKey().toString());
                    name.setString(2, each.getValue());
                    name.executeUpdate();
                }
                for (Map.Entry<UUID, Long> each : measures.entrySet()) {
                    measure.setString(1, each.getKey().toString());
                    measure.setString(2, names.getOrDefault(each.getKey(), ""));
                    measure.setLong(3, each.getValue());
                    measure.executeUpdate();
                }
                for (Map.Entry<UUID, List<Evidence>> each : adds.entrySet()) {
                    for (Evidence evidence : each.getValue()) {
                        bind(insert, each.getKey(), evidence);
                        insert.executeUpdate();
                    }
                    prune.setString(1, each.getKey().toString());
                    prune.setString(2, each.getKey().toString());
                    prune.setInt(3, keep);
                    prune.executeUpdate();
                }
            }
        });
        if (!written) {
            synchronized (lock) {
                flushes.incrementAndGet();
                inFlight = new Batch(Map.of(), Map.of(), Set.of());
                adds.forEach((who, list) -> newEvidence.computeIfAbsent(who, ignored -> new ArrayList<>()).addAll(0, list));
                measures.forEach(newMeasures::putIfAbsent);
                wiped.addAll(clears);
                renamed.addAll(named.keySet());
            }
            return false;
        }
        synchronized (lock) {
            flushes.incrementAndGet();
            inFlight = new Batch(Map.of(), Map.of(), Set.of());
        }
        Set<UUID> touched = new HashSet<>(adds.keySet());
        touched.addAll(measures.keySet());
        touched.addAll(clears);
        touched.addAll(named.keySet());
        touched.forEach(who -> bus.publish(TOPIC, who.toString()));
        return true;
    }

    /**
     * Brings the evidence of 1.x ({@code evidence.yml}) into the database, once. A store on that file brings the
     * file into Core's documents itself; either way the document is marked so a restart does not bring it twice.
     */
    public void importLegacy(YamlStore legacy) {
        var read = legacy.readIfReadable();
        if (read.isEmpty() || read.get().getBoolean("migrated-2.0.0", false)) {
            return;
        }
        var root = read.get().getConfigurationSection("players");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                UUID who = idOf(id);
                if (who == null) {
                    continue;
                }
                for (String line : root.getStringList(id + ".flags")) {
                    Evidence.decode(line).ifPresent(evidence -> add(who, null, evidence));
                }
                String name = root.getString(id + ".name");
                if (name != null && !name.isEmpty()) {
                    synchronized (lock) {
                        names.put(who, name);
                        renamed.add(who);
                    }
                }
                long from = root.getLong(id + ".measured-from", 0);
                if (from > 0) {
                    measureFrom(who, from);
                }
            }
        }
        if (flush() && legacy.update(document -> document.set("migrated-2.0.0", true))) {
            log.info("The evidence of 1.x is in the network's database now.");
        }
    }

    private static final String INSERT = "INSERT INTO ac_evidence (player, at, check_key, level, detail, world, x, y, z, ping, tps) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static void bind(PreparedStatement insert, UUID who, Evidence evidence) throws java.sql.SQLException {
        insert.setString(1, who.toString());
        insert.setLong(2, evidence.atMillis());
        insert.setString(3, evidence.check());
        insert.setDouble(4, evidence.level());
        insert.setString(5, evidence.detail());
        insert.setString(6, evidence.world());
        insert.setInt(7, evidence.x());
        insert.setInt(8, evidence.y());
        insert.setInt(9, evidence.z());
        insert.setInt(10, evidence.ping());
        insert.setDouble(11, evidence.tps());
    }

    private static UUID idOf(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    private static final class Loaded {
        final Map<UUID, Deque<Evidence>> evidence = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>();
        final Map<UUID, Long> measured = new HashMap<>();
    }
}
