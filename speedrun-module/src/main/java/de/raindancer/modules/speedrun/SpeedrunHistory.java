package de.raindancer.modules.speedrun;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Every run this lobby has played, kept across restarts in {@code history.yml} — and everything
 * worked out from it: personal bests, records, the best time ever at each split, leaderboards.
 *
 * <p>Reads are answered from memory; a new run is written through Core's {@link YamlStore} on the
 * given executor (the async scheduler in production), so a finish never waits on the disk.
 */
public final class SpeedrunHistory {

    private static final LogChannel log = Log.of("speedrun");

    /** Which runs a leaderboard lists. */
    public record Filter(SpeedrunCategory category, int playerCount) {

        /** Any number of players. */
        public static final int ANY_COUNT = 0;

        boolean matches(SpeedrunRunRecord run) {
            return run.category().equals(category) && (playerCount == ANY_COUNT || run.playerCount() == playerCount);
        }
    }

    private final YamlStore store;
    private final Executor writer;
    private final List<SpeedrunRunRecord> runs = new CopyOnWriteArrayList<>();
    private volatile BooleanSupplier editedRunsRank = () -> false;
    /** Set when the file could not be read: writing would replace whatever is in it with one run. */
    private volatile boolean readOnly;

    public SpeedrunHistory(YamlStore store, Executor writer) {
        this.store = store;
        this.writer = writer;
    }

    /** Reads {@code history.yml}; a run that cannot be read is skipped with a warning, never fatal. */
    public void load() {
        runs.clear();
        readOnly = false;
        if (store == null || !store.exists()) {
            return;
        }
        YamlConfiguration file = store.read();
        if (!store.problems().isEmpty()) {
            readOnly = true;
            log.warn("The speedrun history {} could not be read ({}). New runs are kept until the next "
                    + "restart but not written, so the file is not replaced — fix or move it.",
                    store.file(), String.join("; ", store.problems()));
            return;
        }
        ConfigurationSection all = file.getConfigurationSection("runs");
        if (all == null) {
            return;
        }
        int skipped = 0;
        for (String id : all.getKeys(false)) {
            Optional<SpeedrunRunRecord> run = SpeedrunRunRecord.readFrom(id, all.getConfigurationSection(id));
            if (run.isPresent()) {
                runs.add(run.get());
            } else {
                skipped++;
            }
        }
        runs.sort(Comparator.comparingLong(SpeedrunRunRecord::startedAt));
        if (skipped > 0) {
            log.warn("{} run(s) in the speedrun history could not be read and were skipped.", skipped);
        }
    }

    /** Whether resumed and hand-edited runs rank — {@code rank-edited-runs}. */
    public void editedRunsRank(boolean rank) {
        this.editedRunsRank = () -> rank;
    }

    /** The same, asked every time — the setting as it stands, not as it stood when this was built. */
    public void editedRunsRankBy(BooleanSupplier rank) {
        this.editedRunsRank = rank == null ? () -> false : rank;
    }

    /** Keeps {@code run} and writes it out. */
    public void add(SpeedrunRunRecord run) {
        runs.add(run);
        if (store == null || readOnly) {
            return;
        }
        writer.execute(() -> {
            boolean written = store.update(file -> run.writeTo(file.createSection("runs." + run.id())));
            if (!written) {
                log.warn("The run {} could not be written to the speedrun history.", run.id());
            }
        });
    }

    /** Every run, oldest first. */
    public List<SpeedrunRunRecord> all() {
        return List.copyOf(runs);
    }

    public Optional<SpeedrunRunRecord> byId(String id) {
        return runs.stream().filter(run -> run.id().equals(id)).findFirst();
    }

    /** {@code player}'s runs, newest first. */
    public List<SpeedrunRunRecord> runsOf(UUID player) {
        List<SpeedrunRunRecord> theirs = new ArrayList<>(runs.stream().filter(run -> run.raced(player)).toList());
        theirs.sort(Comparator.comparingLong(SpeedrunRunRecord::startedAt).reversed());
        return theirs;
    }

    /** Every run, newest first. */
    public List<SpeedrunRunRecord> newestFirst() {
        List<SpeedrunRunRecord> all = new ArrayList<>(runs);
        all.sort(Comparator.comparingLong(SpeedrunRunRecord::startedAt).reversed());
        return all;
    }

    /** {@code player}'s fastest ranked run in {@code category}. */
    public Optional<SpeedrunRunRecord> personalBest(UUID player, SpeedrunCategory category) {
        return fastest(run -> run.category().equals(category) && run.raced(player));
    }

    /** The fastest ranked run in {@code category} — the server record. */
    public Optional<SpeedrunRunRecord> record(SpeedrunCategory category) {
        return fastest(run -> run.category().equals(category));
    }

    /** The best time any ranked run in {@code category} reached {@code milestoneId} at. */
    public Optional<Duration> bestSplit(SpeedrunCategory category, String milestoneId) {
        return ranked(run -> run.category().equals(category)).stream()
                .map(run -> run.splitAt(milestoneId))
                .flatMap(Optional::stream)
                .min(Comparator.naturalOrder());
    }

    /** The ranked runs {@code filter} names, fastest first. */
    public List<SpeedrunRunRecord> leaderboard(Filter filter) {
        List<SpeedrunRunRecord> board = new ArrayList<>(ranked(filter::matches));
        board.sort(Comparator.comparing(SpeedrunRunRecord::time).thenComparingLong(SpeedrunRunRecord::startedAt));
        return board;
    }

    /** Every category anything was ever run in, most played first. */
    public List<SpeedrunCategory> categories() {
        Set<SpeedrunCategory> seen = new LinkedHashSet<>();
        newestFirst().forEach(run -> seen.add(run.category()));
        List<SpeedrunCategory> ordered = new ArrayList<>(seen);
        ordered.sort(Comparator.comparingLong((SpeedrunCategory category) ->
                runs.stream().filter(run -> run.category().equals(category)).count()).reversed());
        return ordered;
    }

    /** Whether any run was played on {@code seed} — a seed somebody has seen is not a random one. */
    public boolean played(long seed) {
        return runs.stream().anyMatch(run -> run.seed() == seed);
    }

    public int size() {
        return runs.size();
    }

    private Optional<SpeedrunRunRecord> fastest(Predicate<SpeedrunRunRecord> which) {
        return ranked(which).stream().min(Comparator.comparing(SpeedrunRunRecord::time)
                .thenComparingLong(SpeedrunRunRecord::startedAt));
    }

    private List<SpeedrunRunRecord> ranked(Predicate<SpeedrunRunRecord> which) {
        boolean edited = editedRunsRank.getAsBoolean();
        return runs.stream().filter(run -> run.ranked(edited)).filter(which).toList();
    }
}
