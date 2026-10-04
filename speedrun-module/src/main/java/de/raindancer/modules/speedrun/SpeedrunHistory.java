package de.raindancer.modules.speedrun;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.raindancer.core.data.runs.Run;
import de.raindancer.core.data.runs.RunHistory;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.speedrun.manhunt.stats.PlayerResult;
import de.raindancer.modules.speedrun.manhunt.stats.PlayerStats;
import de.raindancer.modules.speedrun.manhunt.stats.Rating;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Every run this lobby has played, kept in Core's {@link RunHistory} — and everything worked out from
 * it: personal bests, records, the best time ever at each split, leaderboards.
 *
 * <h2>What Core keeps, and what is kept beside it</h2>
 * Each run is a Core {@link Run}: a timed run on its board ({@link SpeedrunCategory#boardName}), its
 * racers with the names they had, its splits by name — so Core's leaderboard screen and chat lines
 * show it as they show any game's. Everything only this lobby reads (the whole timeline, the per-player
 * results of a game with sides, the seed, how it ended) rides along in the run's fields. The standings
 * of games with sides — a rating is the sum of every rated run ever played, and some predate any run
 * here — are not runs, so they live in {@code standings.yml}.
 *
 * <h2>Which runs rank</h2>
 * Asked of each run itself ({@link SpeedrunRunRecord#ranked(boolean)}): a resumed or hand-edited run is
 * kept but only ranks where {@code rank-edited-runs} says so. Core's copy of that flag follows the
 * setting ({@link #rerank()}), so its boards agree with this.
 *
 * <p>Reads are answered from memory. A new run is written by Core off the server's threads; standings
 * on the given executor.
 */
public final class SpeedrunHistory {

    /** The game's name in Core's run history. */
    public static final String GAME = "speedrun";

    private static final LogChannel log = Log.of("speedrun");

    /** The winning side of a game with sides, as {@link SpeedrunRunRecord#winner()} names it. */
    public static final String RUNNERS = "runners";
    public static final String HUNTERS = "hunters";

    private final RunHistory runs;
    private final YamlStore standingsStore;
    private final Executor writer;
    /** Core's runs, read back into the lobby's own shape once each — a run never changes but for its rank. */
    private final Map<String, SpeedrunRunRecord> decoded = new ConcurrentHashMap<>();
    /**
     * Runs from an old {@code history.yml} not moved into Core's history yet — shown meanwhile, never
     * written from here. See {@link SpeedrunHistoryMigration}.
     */
    private final Map<String, SpeedrunRunRecord> waiting = new ConcurrentHashMap<>();
    /** Per game with sides, each player's standing — rating, wins on each side, catches and the rest. */
    private final Map<String, Map<UUID, PlayerStats>> standings = new ConcurrentHashMap<>();
    private volatile BooleanSupplier editedRunsRank = () -> false;
    /** Set when standings.yml could not be read: writing would replace whatever is in it. */
    private volatile boolean standingsReadOnly;

    public SpeedrunHistory(RunHistory runs, YamlStore standingsStore, Executor writer) {
        this.runs = runs;
        this.standingsStore = standingsStore;
        this.writer = writer;
        loadStandings();
    }

    /** Core's history behind this one — what its leaderboard screen and chat lines read. */
    public RunHistory runs() {
        return runs;
    }

    private void loadStandings() {
        standings.clear();
        standingsReadOnly = false;
        if (standingsStore == null || !standingsStore.exists()) {
            return;
        }
        YamlConfiguration file = standingsStore.read();
        if (!standingsStore.problems().isEmpty()) {
            standingsReadOnly = true;
            log.warn("The standings {} could not be read ({}). Ratings are kept until the next restart but "
                    + "not written, so the file is not replaced — fix or move it.",
                    standingsStore.file(), String.join("; ", standingsStore.problems()));
            return;
        }
        ConfigurationSection kept = file.getConfigurationSection("standings");
        if (kept != null) {
            for (String mode : kept.getKeys(false)) {
                standings.put(mode, readStandings(kept.getConfigurationSection(mode)));
            }
        }
    }

    // ---------------------------------------------------------------------------- ranking edited runs

    /** Whether resumed and hand-edited runs rank — {@code rank-edited-runs}. */
    public void editedRunsRank(boolean rank) {
        this.editedRunsRank = () -> rank;
        rerank();
    }

    /** The same, asked every time — the setting as it stands, not as it stood when this was built. */
    public void editedRunsRankBy(BooleanSupplier rank) {
        this.editedRunsRank = rank == null ? () -> false : rank;
        rerank();
    }

    /** Brings Core's ranked flag of every run in line with the setting as it stands now. */
    public void rerank() {
        boolean edited = editedRunsRank.getAsBoolean();
        for (Run run : runs.newestFirst()) {
            SpeedrunRunRecord record = decode(run);
            if (record != null && record.ranked(edited) != run.ranked()) {
                runs.rank(run.id(), record.ranked(edited));
            }
        }
    }

    // ---------------------------------------------------------------------------- adding

    /** Keeps {@code run}. */
    public void add(SpeedrunRunRecord run) {
        add(run, false);
    }

    /**
     * Keeps {@code run}, and — when {@code rated} — moves every player's standing in its game: wins,
     * catches, and a rating that rises for the winning side and falls for the losing one. A run with
     * nobody winning, or with one side empty, moves no rating.
     */
    public void add(SpeedrunRunRecord run, boolean rated) {
        runs.add(toRun(run, editedRunsRank.getAsBoolean()));
        decoded.put(run.id(), run);
        waiting.remove(run.id());
        String mode = run.category().mode();
        if (rated && !run.players().isEmpty()) {
            rate(mode, run);
            writeStandings(mode);
        }
    }

    /**
     * Runs read from an old history file, shown until they are moved — see
     * {@link SpeedrunHistoryMigration}. One already in Core's history is not shown twice.
     */
    void remember(Collection<SpeedrunRunRecord> legacy) {
        for (SpeedrunRunRecord run : legacy) {
            if (runs.byId(run.id()).isEmpty()) {
                waiting.put(run.id(), run);
            }
        }
    }

    /**
     * Moves {@code legacy} into Core's history — each run whose id Core does not have yet, as it is;
     * a run already there is never replaced.
     *
     * @return how many were moved
     */
    int move(Collection<SpeedrunRunRecord> legacy) {
        int moved = 0;
        boolean edited = editedRunsRank.getAsBoolean();
        for (SpeedrunRunRecord run : legacy) {
            if (runs.byId(run.id()).isEmpty()) {
                runs.add(toRun(run, edited));
                decoded.put(run.id(), run);
                moved++;
            }
            waiting.remove(run.id());
        }
        return moved;
    }

    /** Writes whatever Core has not written yet; off the server's threads. @return whether all is on disk */
    public boolean flush() {
        return runs.flush();
    }

    private synchronized void rate(String mode, SpeedrunRunRecord run) {
        Map<UUID, PlayerStats> table = standingsOf(mode);
        Map<UUID, Double> runners = new LinkedHashMap<>();
        Map<UUID, Double> hunters = new LinkedHashMap<>();
        // A latecomer's result moves nobody's rating, their own included: they did not play the
        // hunt the others were rated on.
        List<PlayerResult> rated = run.players().stream().filter(result -> !run.joinedLate(result.id())).toList();
        for (PlayerResult result : rated) {
            (result.runner() ? runners : hunters).put(result.id(), rating(mode, result.id()));
        }
        boolean decided = RUNNERS.equals(run.winner()) || HUNTERS.equals(run.winner());
        Map<UUID, Double> after = !decided || runners.isEmpty() || hunters.isEmpty() ? new LinkedHashMap<>()
                : Rating.afterHunt(runners, hunters, RUNNERS.equals(run.winner()));
        for (PlayerResult result : rated) {
            PlayerStats before = table.getOrDefault(result.id(), PlayerStats.fresh(result.name()));
            table.put(result.id(), before.plus(result, after.getOrDefault(result.id(), before.rating())));
        }
    }

    // ---------------------------------------------------------------------------- standings

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
     * Standings carried over from before this file kept them — Manhunt's own {@code stats.yml}, an old
     * {@code history.yml} — taken as they are, so nobody's rating moves by the move. Somebody who already
     * has a standing here keeps theirs.
     */
    public void importStandings(String mode, Map<UUID, PlayerStats> imported) {
        Map<UUID, PlayerStats> table = standingsOf(mode);
        imported.forEach(table::putIfAbsent);
        writeStandings(mode);
    }

    /** The same, written before this returns — for a move that may only delete its source once it is. */
    public boolean importStandingsNow(String mode, Map<UUID, PlayerStats> imported) {
        Map<UUID, PlayerStats> table = standingsOf(mode);
        imported.forEach(table::putIfAbsent);
        return writeStandingsNow(mode, Map.copyOf(table));
    }

    private void writeStandings(String mode) {
        if (standingsStore == null || standingsReadOnly) {
            return;
        }
        Map<UUID, PlayerStats> snapshot = Map.copyOf(standingsOf(mode));
        writer.execute(() -> {
            if (!writeStandingsNow(mode, snapshot)) {
                log.warn("The {} standings could not be written.", mode);
            }
        });
    }

    private boolean writeStandingsNow(String mode, Map<UUID, PlayerStats> snapshot) {
        if (standingsStore == null || standingsReadOnly) {
            return false;
        }
        return standingsStore.update(file -> writeStandings(file.createSection("standings." + mode), snapshot));
    }

    static Map<UUID, PlayerStats> readStandings(ConfigurationSection section) {
        Map<UUID, PlayerStats> table = new ConcurrentHashMap<>();
        if (section == null) {
            return table;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection p = section.getConfigurationSection(key);
            try {
                table.put(UUID.fromString(key), readStanding(p));
            } catch (IllegalArgumentException | NullPointerException notOne) {
                log.warn("A standing could not be read and was skipped: {}", key);
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

    // ---------------------------------------------------------------------------- asking

    /** Every run, oldest first. */
    public List<SpeedrunRunRecord> all() {
        Map<String, SpeedrunRunRecord> every = new LinkedHashMap<>(waiting);
        for (Run run : runs.newestFirst()) {
            SpeedrunRunRecord record = decode(run);
            if (record != null) {
                every.put(record.id(), record);
            }
        }
        List<SpeedrunRunRecord> ordered = new ArrayList<>(every.values());
        ordered.sort(Comparator.comparingLong(SpeedrunRunRecord::startedAt).thenComparing(SpeedrunRunRecord::id));
        return ordered;
    }

    public Optional<SpeedrunRunRecord> byId(String id) {
        SpeedrunRunRecord legacy = waiting.get(id);
        return legacy != null ? Optional.of(legacy) : runs.byId(id).map(this::decode);
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

    /** {@code player}'s runs, newest first. */
    public List<SpeedrunRunRecord> runsOf(UUID player) {
        return newestFirst().stream().filter(run -> run.raced(player)).toList();
    }

    /** Every run, newest first. */
    public List<SpeedrunRunRecord> newestFirst() {
        List<SpeedrunRunRecord> all = new ArrayList<>(all());
        java.util.Collections.reverse(all);
        return all;
    }

    /** {@code player}'s fastest ranked run in {@code category}, whatever the number of players. */
    public Optional<SpeedrunRunRecord> personalBest(UUID player, SpeedrunCategory category) {
        return fastest(run -> run.category().equals(category) && run.ranFromTheStart(player));
    }

    /** The fastest ranked run in {@code category} — the server record, whatever the number of players. */
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

    /** The ranked runs of {@code category} with {@code players} racers — any number for 0 — fastest first. */
    public List<SpeedrunRunRecord> leaderboard(SpeedrunCategory category, int players) {
        List<SpeedrunRunRecord> board = new ArrayList<>(ranked(run -> run.category().equals(category)
                && (players <= 0 || run.playerCount() == players)));
        board.sort(Comparator.comparing(SpeedrunRunRecord::time).thenComparingLong(SpeedrunRunRecord::startedAt));
        return board;
    }

    /** Every category anything was ever run in, most played first. */
    public List<SpeedrunCategory> categories() {
        Map<SpeedrunCategory, Long> counts = new LinkedHashMap<>();
        newestFirst().forEach(run -> counts.merge(run.category(), 1L, Long::sum));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<SpeedrunCategory, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
    }

    /** Whether any run was played on {@code seed} — a seed somebody has seen is not a random one. */
    public boolean played(long seed) {
        return all().stream().anyMatch(run -> run.seed() == seed);
    }

    public int size() {
        return all().size();
    }

    private Optional<SpeedrunRunRecord> fastest(Predicate<SpeedrunRunRecord> which) {
        return ranked(which).stream().min(Comparator.comparing(SpeedrunRunRecord::time)
                .thenComparingLong(SpeedrunRunRecord::startedAt));
    }

    private List<SpeedrunRunRecord> ranked(Predicate<SpeedrunRunRecord> which) {
        boolean edited = editedRunsRank.getAsBoolean();
        return all().stream().filter(run -> run.ranked(edited)).filter(which).toList();
    }

    // ---------------------------------------------------------------------------- Core's shape

    /** {@code record} as Core keeps it. */
    static Run toRun(SpeedrunRunRecord record, boolean editedRank) {
        Run.Builder run = Run.timed(record.category().boardName(record.playerCount()), record.time())
                .id(record.id())
                .startedAt(Instant.ofEpochMilli(record.startedAt()))
                .players(fromTheStart(record));
        Set<String> named = new LinkedHashSet<>();
        for (SpeedrunTimeline.Entry split : record.splits()) {
            String name = record.labelOf(split.detail());
            if (named.add(name)) {
                run.split(name, split.at());
            }
        }
        if (!record.ranked(editedRank)) {
            run.unranked();
        }
        run.field("category", record.category().key())
                .field("outcome", record.outcome())
                .field("completed", record.completed())
                .field("seed", record.seed())
                .field("timeline", timelineJson(record.timeline()));
        if (!record.labels().isEmpty()) {
            JsonObject labels = new JsonObject();
            record.labels().forEach(labels::addProperty);
            run.field("labels", labels.toString());
        }
        if (!record.players().isEmpty()) {
            run.field("results", resultsJson(record.players()));
        }
        if (!record.winner().isEmpty()) {
            run.field("winner", record.winner());
        }
        JsonObject latecomers = new JsonObject();
        record.participants().forEach((id, name) -> {
            if (record.joinedLate(id)) {
                latecomers.addProperty(id.toString(), name);
            }
        });
        if (latecomers.size() > 0) {
            // Not among Core's players — no board credits them — but racers of this run all the same.
            run.field("latecomers", latecomers.toString());
        }
        return run.build();
    }

    /** Who Core credits with the run: everybody who ran it from the start — a latecomer is not. */
    private static Map<UUID, String> fromTheStart(SpeedrunRunRecord record) {
        Map<UUID, String> credited = new LinkedHashMap<>();
        record.participants().forEach((id, name) -> {
            if (!record.joinedLate(id)) {
                credited.put(id, name);
            }
        });
        return credited;
    }

    private SpeedrunRunRecord decode(Run run) {
        SpeedrunRunRecord known = decoded.get(run.id());
        if (known != null) {
            return known;
        }
        SpeedrunRunRecord read = fromRun(run).orElse(null);
        if (read != null) {
            decoded.put(run.id(), read);
        }
        return read;
    }

    /** Core's run in the lobby's own shape — empty for one this lobby did not write. */
    static Optional<SpeedrunRunRecord> fromRun(Run run) {
        Optional<SpeedrunCategory> category = run.field("category").flatMap(SpeedrunCategory::fromKey);
        if (category.isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<UUID, String> participants = new LinkedHashMap<>(run.players());
            run.field("latecomers").map(JsonParser::parseString).filter(JsonElement::isJsonObject)
                    .ifPresent(json -> json.getAsJsonObject().entrySet().forEach(entry ->
                            participants.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString())));
            Map<String, String> labels = new LinkedHashMap<>();
            run.field("labels").map(JsonParser::parseString).filter(JsonElement::isJsonObject)
                    .ifPresent(json -> json.getAsJsonObject().entrySet()
                            .forEach(entry -> labels.put(entry.getKey(), entry.getValue().getAsString())));
            return Optional.of(new SpeedrunRunRecord(run.id(), category.get(), run.startedAt().toEpochMilli(),
                    run.time(), run.field("outcome").orElse(""),
                    Boolean.parseBoolean(run.field("completed").orElse("false")),
                    run.field("seed").map(Long::parseLong).orElse(0L), participants,
                    readTimeline(run.field("timeline").orElse("[]")), labels,
                    readResults(run.field("results").orElse("[]")), run.field("winner").orElse("")));
        } catch (RuntimeException unreadable) {
            log.warn("Run {} in the speedrun history could not be read and was skipped.", run.id());
            return Optional.empty();
        }
    }

    private static String timelineJson(List<SpeedrunTimeline.Entry> timeline) {
        JsonArray entries = new JsonArray();
        for (SpeedrunTimeline.Entry entry : timeline) {
            JsonObject json = new JsonObject();
            json.addProperty("kind", entry.kind().name());
            json.addProperty("at", entry.at().toMillis());
            if (entry.who() != null) {
                json.addProperty("who", entry.who().toString());
            }
            json.addProperty("detail", entry.detail());
            if (entry.other() != null) {
                json.addProperty("other", entry.other().toString());
            }
            entries.add(json);
        }
        return entries.toString();
    }

    private static List<SpeedrunTimeline.Entry> readTimeline(String json) {
        List<SpeedrunTimeline.Entry> entries = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject entry = element.getAsJsonObject();
            SpeedrunTimeline.Kind kind;
            try {
                kind = SpeedrunTimeline.Kind.valueOf(entry.get("kind").getAsString());
            } catch (IllegalArgumentException fromANewerVersion) {
                continue;   // an entry kind from a newer version: skipped, the rest of the run still reads
            }
            entries.add(new SpeedrunTimeline.Entry(kind, Duration.ofMillis(entry.get("at").getAsLong()),
                    entry.has("who") ? UUID.fromString(entry.get("who").getAsString()) : null,
                    entry.has("detail") ? entry.get("detail").getAsString() : "",
                    entry.has("other") ? UUID.fromString(entry.get("other").getAsString()) : null));
        }
        return entries;
    }

    private static String resultsJson(List<PlayerResult> results) {
        JsonArray all = new JsonArray();
        for (PlayerResult p : results) {
            JsonObject json = new JsonObject();
            json.addProperty("id", p.id().toString());
            json.addProperty("name", p.name());
            json.addProperty("runner", p.runner());
            json.addProperty("won", p.won());
            json.addProperty("caught", p.caught());
            json.addProperty("catches", p.catches());
            json.addProperty("deaths", p.deaths());
            json.addProperty("survived-millis", p.survivedMillis());
            json.addProperty("distance", p.distance());
            json.addProperty("portals", p.portals());
            all.add(json);
        }
        return all.toString();
    }

    private static List<PlayerResult> readResults(String json) {
        List<PlayerResult> results = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject r = element.getAsJsonObject();
            results.add(new PlayerResult(UUID.fromString(r.get("id").getAsString()), r.get("name").getAsString(),
                    r.get("runner").getAsBoolean(), r.get("won").getAsBoolean(), r.get("caught").getAsBoolean(),
                    r.get("catches").getAsInt(), r.get("deaths").getAsInt(), r.get("survived-millis").getAsLong(),
                    r.get("distance").getAsDouble(), r.get("portals").getAsInt()));
        }
        return results;
    }
}
