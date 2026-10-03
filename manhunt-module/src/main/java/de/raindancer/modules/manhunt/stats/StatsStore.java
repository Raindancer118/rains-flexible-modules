package de.raindancer.modules.manhunt.stats;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * Every player's numbers across hunts, kept in {@code stats.yml} beside the module's settings.
 *
 * <p>Read once and held in memory — the leaderboard is sorted on every page turn — and written whole
 * after each hunt. Synchronized: a hunt ends on whatever thread its last catch landed on.
 */
public final class StatsStore {

    /** What a leaderboard is sorted by. */
    public enum Board {
        RATING(PlayerStats::rating),
        WINS(stats -> stats.wins()),
        CATCHES(stats -> stats.catches()),
        SURVIVAL(stats -> stats.bestSurvivalMillis()),
        DISTANCE(PlayerStats::distance);

        private final ToDoubleFunction<PlayerStats> score;

        Board(ToDoubleFunction<PlayerStats> score) {
            this.score = score;
        }

        public double scoreOf(PlayerStats stats) {
            return score.applyAsDouble(stats);
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public Board next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static Optional<Board> byId(String id) {
            for (Board board : values()) {
                if (board.id().equalsIgnoreCase(id)) {
                    return Optional.of(board);
                }
            }
            return Optional.empty();
        }
    }

    private final YamlStore store;
    private final Map<UUID, PlayerStats> players = new LinkedHashMap<>();

    public StatsStore(Path file) {
        this.store = new YamlStore(file);
        load();
    }

    private void load() {
        if (!store.exists()) {
            return;
        }
        ConfigurationSection section = store.read().getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection p = section.getConfigurationSection(key);
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException notAnId) {
                continue;
            }
            players.put(id, new PlayerStats(p.getString("name", "somebody"),
                    p.getDouble("rating", Rating.START), p.getInt("hunts"), p.getInt("runner-hunts"),
                    p.getInt("runner-wins"), p.getInt("hunter-hunts"), p.getInt("hunter-wins"),
                    p.getInt("catches"), p.getInt("deaths"), p.getInt("times-caught"),
                    p.getLong("survived-millis"), p.getLong("best-survival-millis"), p.getDouble("distance"),
                    p.getInt("portals")));
        }
    }

    public synchronized PlayerStats get(UUID id) {
        return players.getOrDefault(id, PlayerStats.fresh("somebody"));
    }

    public synchronized boolean has(UUID id) {
        return players.containsKey(id);
    }

    public synchronized double rating(UUID id) {
        PlayerStats stats = players.get(id);
        return stats == null ? Rating.START : stats.rating();
    }

    /** A name as last seen in a hunt, ignoring case. */
    public synchronized Optional<UUID> byName(String name) {
        return players.entrySet().stream()
                .filter(entry -> entry.getValue().name().equalsIgnoreCase(name))
                .map(Map.Entry::getKey).findFirst();
    }

    public synchronized List<PlayerStats> top(Board board, int limit) {
        return players.values().stream()
                .sorted(Comparator.comparingDouble(board::scoreOf).reversed()
                        .thenComparing(PlayerStats::name))
                .limit(Math.max(0, limit)).toList();
    }

    public synchronized List<UUID> ranked(Board board) {
        return players.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<UUID, PlayerStats> e) -> board.scoreOf(e.getValue()))
                        .reversed().thenComparing(e -> e.getValue().name()))
                .map(Map.Entry::getKey).toList();
    }

    /** One finished hunt, added to everybody in it, ratings moved where somebody won. Saved at once. */
    public synchronized void record(HuntRecord record) {
        Map<UUID, Double> runners = new LinkedHashMap<>();
        Map<UUID, Double> hunters = new LinkedHashMap<>();
        for (PlayerResult result : record.players()) {
            (result.runner() ? runners : hunters).put(result.id(), rating(result.id()));
        }
        Map<UUID, Double> after = record.winner() == HuntRecord.Winner.NOBODY || runners.isEmpty() || hunters.isEmpty()
                ? new LinkedHashMap<>()
                : Rating.afterHunt(runners, hunters, record.winner() == HuntRecord.Winner.RUNNERS);
        for (PlayerResult result : record.players()) {
            PlayerStats before = players.getOrDefault(result.id(), PlayerStats.fresh(result.name()));
            players.put(result.id(), before.plus(result, after.getOrDefault(result.id(), before.rating())));
        }
        save();
    }

    private void save() {
        Map<UUID, PlayerStats> snapshot = Map.copyOf(players);
        store.write(yaml -> write(yaml, snapshot));
    }

    private static void write(YamlConfiguration yaml, Map<UUID, PlayerStats> players) {
        players.forEach((id, stats) -> {
            String at = "players." + id + ".";
            yaml.set(at + "name", stats.name());
            yaml.set(at + "rating", stats.rating());
            yaml.set(at + "hunts", stats.hunts());
            yaml.set(at + "runner-hunts", stats.runnerHunts());
            yaml.set(at + "runner-wins", stats.runnerWins());
            yaml.set(at + "hunter-hunts", stats.hunterHunts());
            yaml.set(at + "hunter-wins", stats.hunterWins());
            yaml.set(at + "catches", stats.catches());
            yaml.set(at + "deaths", stats.deaths());
            yaml.set(at + "times-caught", stats.timesCaught());
            yaml.set(at + "survived-millis", stats.survivedMillis());
            yaml.set(at + "best-survival-millis", stats.bestSurvivalMillis());
            yaml.set(at + "distance", stats.distance());
            yaml.set(at + "portals", stats.portals());
        });
    }
}
