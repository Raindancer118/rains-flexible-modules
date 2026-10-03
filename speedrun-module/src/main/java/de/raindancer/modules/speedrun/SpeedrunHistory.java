package de.raindancer.modules.speedrun;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.speedrun.manhunt.stats.PlayerResult;
import de.raindancer.modules.speedrun.manhunt.stats.PlayerStats;
import de.raindancer.modules.speedrun.manhunt.stats.Rating;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    /**
     * Per game with sides, each player's standing — rating, wins on each side, catches and the rest.
     * Kept rather than worked out from the runs: a rating is the sum of every rated run ever played,
     * and the runs themselves may predate this file (see {@link #importStandings}).
     */
    private final Map<String, Map<UUID, PlayerStats>> standings = new ConcurrentHashMap<>();
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
        standings.clear();
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
        ConfigurationSection kept = file.getConfigurationSection("standings");
        if (kept != null) {
            for (String mode : kept.getKeys(false)) {
                standings.put(mode, readStandings(kept.getConfigurationSection(mode)));
            }
        }
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
        add(run, false);
    }

    /**
     * Keeps {@code run}, and — when {@code rated} — moves every player's standing in its game: wins,
     * catches, and a rating that rises for the winning side and falls for the losing one. A run with
     * nobody winning, or with one side empty, moves no rating.
     */
    public void add(SpeedrunRunRecord run, boolean rated) {
        runs.add(run);
        String mode = run.category().mode();
        boolean moves = rated && !run.players().isEmpty();
        if (moves) {
            rate(mode, run);
        }
        if (store == null || readOnly) {
            return;
        }
        Map<UUID, PlayerStats> snapshot = moves ? Map.copyOf(standingsOf(mode)) : Map.of();
        writer.execute(() -> {
            boolean written = store.update(file -> {
                run.writeTo(file.createSection("runs." + run.id()));
                if (moves) {
                    writeStandings(file.createSection("standings." + mode), snapshot);
                }
            });
            if (!written) {
                log.warn("The run {} could not be written to the speedrun history.", run.id());
            }
        });
    }

    private synchronized void rate(String mode, SpeedrunRunRecord run) {
        Map<UUID, PlayerStats> table = standingsOf(mode);
        Map<UUID, Double> runners = new LinkedHashMap<>();
        Map<UUID, Double> hunters = new LinkedHashMap<>();
        for (PlayerResult result : run.players()) {
            (result.runner() ? runners : hunters).put(result.id(), rating(mode, result.id()));
        }
        boolean decided = RUNNERS.equals(run.winner()) || HUNTERS.equals(run.winner());
        Map<UUID, Double> after = !decided || runners.isEmpty() || hunters.isEmpty() ? new LinkedHashMap<>()
                : Rating.afterHunt(runners, hunters, RUNNERS.equals(run.winner()));
        for (PlayerResult result : run.players()) {
            PlayerStats before = table.getOrDefault(result.id(), PlayerStats.fresh(result.name()));
            table.put(result.id(), before.plus(result, after.getOrDefault(result.id(), before.rating())));
        }
    }

    /** The winning side of a game with sides, as {@link SpeedrunRunRecord#winner()} names it. */
    public static final String RUNNERS = "runners";
    public static final String HUNTERS = "hunters";

    private Map<UUID, PlayerStats> standingsOf(String mode) {
        return standings.computeIfAbsent(mode, key -> new ConcurrentHashMap<>());
    }

    /** {@code player}'s standing in {@code mode}, if they ever played a rated run of it. */
    public Optional<PlayerStats> standing(String mode, UUID player) {
        return Optional.ofNullable(standings.getOrDefault(mode, Map.of()).get(player));
    }

    /** {@code player}'s rating in {@code mode} — the starting rating for somebody new. */
    public double rating(String mode, UUID player) {
        return standing(mode, player).map(PlayerStats::rating).orElse(Rating.START);
    }

    /** Everybody with a standing in {@code mode}, best first by {@code board}. */
    public List<UUID> ranked(String mode, SpeedrunBoard board) {
        Map<UUID, PlayerStats> table = standings.getOrDefault(mode, Map.of());
        return table.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<UUID, PlayerStats> e) -> board.scoreOf(e.getValue()))
                        .reversed().thenComparing(e -> e.getValue().name()))
                .map(Map.Entry::getKey).toList();
    }

    /** The {@code limit} best standings in {@code mode} by {@code board}. */
    public List<PlayerStats> top(String mode, SpeedrunBoard board, int limit) {
        Map<UUID, PlayerStats> table = standings.getOrDefault(mode, Map.of());
        return ranked(mode, board).stream().limit(Math.max(0, limit)).map(table::get).toList();
    }

    /** Whoever in {@code mode}'s standings goes by {@code name}. */
    public Optional<UUID> byName(String mode, String name) {
        return standings.getOrDefault(mode, Map.of()).entrySet().stream()
                .filter(entry -> entry.getValue().name().equalsIgnoreCase(name))
                .map(Map.Entry::getKey).findFirst();
    }

    /** Every game anybody has a standing in. */
    public Set<String> modesWithStandings() {
        return Set.copyOf(standings.keySet());
    }

    /**
     * Standings carried over from before this file kept them — Manhunt's own {@code stats.yml} — taken
     * as they are, so nobody's rating moves by the move. Somebody who already has a standing here
     * keeps theirs.
     */
    public void importStandings(String mode, Map<UUID, PlayerStats> imported) {
        Map<UUID, PlayerStats> table = standingsOf(mode);
        imported.forEach(table::putIfAbsent);
        if (store == null || readOnly) {
            return;
        }
        Map<UUID, PlayerStats> snapshot = Map.copyOf(table);
        writer.execute(() -> store.update(file -> writeStandings(file.createSection("standings." + mode), snapshot)));
    }

    /** The run numbered {@code number} — the first run ever is 1. */
    public Optional<SpeedrunRunRecord> byNumber(int number) {
        List<SpeedrunRunRecord> all = all();
        return number < 1 || number > all.size() ? Optional.empty() : Optional.of(all.get(number - 1));
    }

    /** {@code run}'s number, 1 for the first run ever kept — 0 for one not in here. */
    public int numberOf(SpeedrunRunRecord run) {
        return all().indexOf(run) + 1;
    }

    private static Map<UUID, PlayerStats> readStandings(ConfigurationSection section) {
        Map<UUID, PlayerStats> table = new ConcurrentHashMap<>();
        if (section == null) {
            return table;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection p = section.getConfigurationSection(key);
            try {
                table.put(UUID.fromString(key), readStanding(p));
            } catch (IllegalArgumentException | NullPointerException notOne) {
                log.warn("A standing in the speedrun history could not be read and was skipped: {}", key);
            }
        }
        return table;
    }

    /** One player's standing, as both this file and Manhunt's old {@code stats.yml} wrote it. */
    public static PlayerStats readStanding(ConfigurationSection p) {
        return new PlayerStats(p.getString("name", "somebody"),
                p.getDouble("rating", Rating.START), p.getInt("hunts"), p.getInt("runner-hunts"),
                p.getInt("runner-wins"), p.getInt("hunter-hunts"), p.getInt("hunter-wins"),
                p.getInt("catches"), p.getInt("deaths"), p.getInt("times-caught"),
                p.getLong("survived-millis"), p.getLong("best-survival-millis"), p.getDouble("distance"),
                p.getInt("portals"));
    }

    private static void writeStandings(ConfigurationSection section, Map<UUID, PlayerStats> table) {
        table.forEach((id, stats) -> {
            ConfigurationSection p = section.createSection(id.toString());
            p.set("name", stats.name());
            p.set("rating", stats.rating());
            p.set("hunts", stats.hunts());
            p.set("runner-hunts", stats.runnerHunts());
            p.set("runner-wins", stats.runnerWins());
            p.set("hunter-hunts", stats.hunterHunts());
            p.set("hunter-wins", stats.hunterWins());
            p.set("catches", stats.catches());
            p.set("deaths", stats.deaths());
            p.set("times-caught", stats.timesCaught());
            p.set("survived-millis", stats.survivedMillis());
            p.set("best-survival-millis", stats.bestSurvivalMillis());
            p.set("distance", stats.distance());
            p.set("portals", stats.portals());
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
