package de.raindancer.modules.moderation.store;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.moderation.model.ApproachReading;
import de.raindancer.modules.moderation.model.MinedBlock;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every ore block worth showing on {@code XrayReviewMenu}, kept across restarts.
 *
 * <h2>Why this exists separately from {@code MiningTrail}</h2>
 * That class keeps the raw, block-by-block dig leading up to each ore — hundreds of ordinary stone
 * blocks for every diamond, which is exactly the context that makes the directness score meaningful,
 * and exactly why it would be the wrong thing to write to disk: a live server's whole mining history
 * for every player who has ever touched a pickaxe. This keeps only the {@link ApproachReading}s
 * already worked out from that context — a handful of numbers and a location, per ore block — which
 * is small enough to remember for good.
 *
 * <p>The gap this closes: a restart used to lose the review screen entirely, even though the
 * long-running probability it feeds — {@code PlayerMiningProfile} — already survived one. A
 * moderator could see "72% probability" and open the review to find nothing behind it, because the
 * actual evidence was session-only. It no longer is.
 *
 * <h2>Why capped rather than kept for ever</h2>
 * An active miner racks up genuinely more ore over months than any review screen needs open at once,
 * and evidence from a year ago is not why anybody opens this page today — see the same reasoning
 * {@code MiningWindow} and {@code ServerMiningBaseline} already give for judging recent behaviour
 * over a lifetime total. The oldest finding is dropped once a player's list is full, the same shape
 * as a rolling window, just measured in ore blocks found rather than blocks mined.
 */
public final class PersistedFindings {

    private static final LogChannel log = Log.of("moderation");

    /**
     * How many findings one player's list holds before the oldest is dropped.
     *
     * <p>Generous rather than exact: this is a moderator's reading list, not a detector's judgement,
     * so there is no harm in keeping more than anybody will look at in one sitting — only in keeping
     * so much that the file grows without bound.
     */
    public static final int CAPACITY_PER_PLAYER = 200;

    private final Map<UUID, Deque<ApproachReading>> findings = new ConcurrentHashMap<>();
    private final YamlStore store;

    public PersistedFindings(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("xray-findings.yml"));
    }

    /** Where this is kept — for a diagnostic, and for a test that wants to break the file. */
    public Path file() {
        return store.file();
    }

    /** Remembers one more finding, dropping the oldest for this player once their list is full. */
    public void add(UUID who, ApproachReading reading) {
        if (who == null || reading == null) {
            return;
        }
        Deque<ApproachReading> theirs = findings.computeIfAbsent(who,
                ignored -> new ArrayDeque<>());
        synchronized (theirs) {
            theirs.addLast(reading);
            while (theirs.size() > CAPACITY_PER_PLAYER) {
                theirs.removeFirst();
            }
        }
    }

    /** Everything remembered about this player, oldest first. */
    public List<ApproachReading> of(UUID who) {
        if (who == null) {
            return List.of();
        }
        Deque<ApproachReading> theirs = findings.get(who);
        if (theirs == null) {
            return List.of();
        }
        synchronized (theirs) {
            return List.copyOf(theirs);
        }
    }

    /** Everybody this has ever recorded a finding about. */
    public java.util.Set<UUID> everybody() {
        return java.util.Set.copyOf(findings.keySet());
    }

    /** Forgets one player entirely — kept apart from a session's end, which forgets nothing here. */
    public void forget(UUID who) {
        if (who != null) {
            findings.remove(who);
        }
    }

    /** Reads what is on disk, replacing what is held. */
    public void load() {
        findings.clear();
        var root = store.read().getConfigurationSection("players");
        if (root == null) {
            return;
        }
        List<String> unreadable = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            UUID who;
            try {
                who = UUID.fromString(id);
            } catch (IllegalArgumentException notAnId) {
                unreadable.add(id);
                continue;
            }
            List<Map<?, ?>> rows = root.getMapList(id);
            Deque<ApproachReading> theirs = new ArrayDeque<>();
            for (Map<?, ?> row : rows) {
                readOne(row).ifPresent(theirs::addLast);
            }
            if (!theirs.isEmpty()) {
                findings.put(who, theirs);
            }
        }
        if (!unreadable.isEmpty()) {
            log.error("{} entry/entries in xray-findings.yml are not player ids and have been "
                            + "skipped: {}. Whoever they belonged to starts this list empty again — "
                            + "the probability that already survives a restart is untouched.",
                    unreadable.size(), String.join(", ", unreadable));
        }
    }

    private static java.util.Optional<ApproachReading> readOne(Map<?, ?> row) {
        try {
            MinedBlock ore = new MinedBlock((String) row.get("world"),
                    (int) row.get("x"), (int) row.get("y"), (int) row.get("z"),
                    (String) row.get("material"));
            return java.util.Optional.of(new ApproachReading(ore,
                    (int) row.get("path-length"), (double) row.get("distance"),
                    (int) row.get("directness")));
        } catch (RuntimeException malformed) {
            // One bad row must not cost every other finding for the same player — skipped in
            // isolation rather than throwing the whole list away.
            return java.util.Optional.empty();
        }
    }

    /** Writes the lot. @return whether it reached the disk */
    public boolean flush() {
        return store.write(yaml -> findings.forEach((who, theirs) -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            synchronized (theirs) {
                for (ApproachReading reading : theirs) {
                    rows.add(rowOf(reading));
                }
            }
            yaml.set("players." + who, rows);
        }));
    }

    private static Map<String, Object> rowOf(ApproachReading reading) {
        MinedBlock ore = reading.ore();
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("world", ore.world());
        row.put("x", ore.x());
        row.put("y", ore.y());
        row.put("z", ore.z());
        row.put("material", ore.material());
        row.put("path-length", reading.pathLength());
        row.put("distance", reading.straightLineDistance());
        row.put("directness", reading.directnessPercent());
        return Collections.unmodifiableMap(row);
    }
}
