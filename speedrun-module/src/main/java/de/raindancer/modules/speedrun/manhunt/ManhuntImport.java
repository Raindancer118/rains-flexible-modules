package de.raindancer.modules.speedrun.manhunt;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.speedrun.SpeedrunCategory;
import de.raindancer.modules.speedrun.SpeedrunHistory;
import de.raindancer.modules.speedrun.SpeedrunMilestones;
import de.raindancer.modules.speedrun.SpeedrunRunRecord;
import de.raindancer.modules.speedrun.SpeedrunSeedType;
import de.raindancer.modules.speedrun.SpeedrunTimeline;
import de.raindancer.modules.speedrun.manhunt.mode.ManhuntMode;
import de.raindancer.modules.speedrun.manhunt.stats.PlayerResult;
import de.raindancer.modules.speedrun.manhunt.stats.PlayerStats;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manhunt's own stats and past hunts, as the separate plugin kept them ({@code stats.yml},
 * {@code hunts.yml} — carried into this folder by {@link ManhuntMigration}), taken into the one
 * speedrun history: the standings exactly as they were, so nobody's rating moves by the move, and
 * every hunt as a run with its players, its catches and its splits — added without moving any
 * rating again, since the old stats already counted it.
 *
 * <p>Each file is renamed to {@code .imported} once it has been read, never deleted, and a hunt
 * already in the history is never added twice.
 */
public final class ManhuntImport {

    private static final LogChannel log = Log.of("speedrun");

    /** Manhunt's old milestone ids, as the lobby's splits know them. */
    private static final Map<String, String> MILESTONES = Map.of(
            "nether", SpeedrunMilestones.ENTER_NETHER.id(),
            "fortress", SpeedrunMilestones.FORTRESS.id(),
            "blaze-rod", SpeedrunMilestones.BLAZE_ROD.id(),
            "stronghold", SpeedrunMilestones.STRONGHOLD.id(),
            "end", SpeedrunMilestones.ENTER_END.id(),
            "dragon-half", SpeedrunMilestones.DRAGON_HALF.id(),
            "dragon-killed", SpeedrunMilestones.DRAGON_KILL.id());

    private ManhuntImport() {
    }

    public static void run(Path folder, SpeedrunHistory history) {
        if (history == null) {
            return;
        }
        Path stats = folder.resolve("stats.yml");
        if (Files.isRegularFile(stats)) {
            YamlStore store = new YamlStore(stats);
            YamlConfiguration yaml = store.read();
            if (!store.problems().isEmpty()) {
                log.warn("Manhunt's old {} could not be read ({}); it is left as it is.", stats,
                        String.join("; ", store.problems()));
            } else {
                Map<UUID, PlayerStats> standings = new LinkedHashMap<>();
                ConfigurationSection players = yaml.getConfigurationSection("players");
                if (players != null) {
                    for (String key : players.getKeys(false)) {
                        try {
                            standings.put(UUID.fromString(key), SpeedrunHistory.readStanding(players.getConfigurationSection(key)));
                        } catch (RuntimeException unreadable) {
                            log.warn("A player in Manhunt's old stats could not be read and was skipped: {}", key);
                        }
                    }
                }
                history.importStandings(ManhuntMode.ID, standings);
                setAside(stats);
                log.info("{} Manhunt rating(s) carried into the speedrun history.", standings.size());
            }
        }
        Path hunts = folder.resolve("hunts.yml");
        if (Files.isRegularFile(hunts)) {
            YamlStore store = new YamlStore(hunts);
            YamlConfiguration yaml = store.read();
            if (!store.problems().isEmpty()) {
                log.warn("Manhunt's old {} could not be read ({}); it is left as it is.", hunts,
                        String.join("; ", store.problems()));
                return;
            }
            int added = 0;
            ConfigurationSection all = yaml.getConfigurationSection("hunts");
            List<SpeedrunRunRecord> read = new ArrayList<>();
            if (all != null) {
                for (String key : all.getKeys(false)) {
                    try {
                        read.add(hunt(all.getConfigurationSection(key)));
                    } catch (RuntimeException unreadable) {
                        log.warn("A hunt in Manhunt's old history could not be read and was skipped: {}", key);
                    }
                }
            }
            read.sort(Comparator.comparingLong(SpeedrunRunRecord::startedAt));
            for (SpeedrunRunRecord run : read) {
                if (history.all().stream().noneMatch(kept -> kept.id().equals(run.id()))) {
                    history.add(run, false);
                    added++;
                }
            }
            setAside(hunts);
            log.info("{} past hunt(s) carried into the speedrun history.", added);
        }
    }

    private static SpeedrunRunRecord hunt(ConfigurationSection hunt) {
        int number = hunt.getInt("number");
        String reason = hunt.getString("reason", "");
        String winner = switch (hunt.getString("winner", "NOBODY")) {
            case "RUNNERS" -> SpeedrunHistory.RUNNERS;
            case "HUNTERS" -> SpeedrunHistory.HUNTERS;
            default -> "";
        };
        Map<UUID, String> names = new LinkedHashMap<>();
        List<PlayerResult> results = new ArrayList<>();
        ConfigurationSection players = hunt.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                ConfigurationSection p = players.getConfigurationSection(key);
                UUID id = UUID.fromString(key);
                names.put(id, p.getString("name", ""));
                results.add(new PlayerResult(id, p.getString("name", ""), p.getBoolean("runner"), p.getBoolean("won"),
                        p.getBoolean("caught"), p.getInt("catches"), p.getInt("deaths"), p.getLong("survived-millis"),
                        p.getDouble("distance"), p.getInt("portals")));
            }
        }
        List<SpeedrunTimeline.Entry> timeline = new ArrayList<>();
        ConfigurationSection events = hunt.getConfigurationSection("events");
        if (events != null) {
            for (String key : events.getKeys(false)) {
                ConfigurationSection e = events.getConfigurationSection(key);
                entry(e).ifPresent(timeline::add);
            }
        }
        timeline.sort(Comparator.comparing(SpeedrunTimeline.Entry::at));
        return new SpeedrunRunRecord("manhunt-hunt-" + number,
                new SpeedrunCategory("", SpeedrunSeedType.RANDOM, ManhuntMode.ID, ""),
                hunt.getLong("started-at"), Duration.ofMillis(hunt.getLong("duration-millis")), reason,
                reason.startsWith("advancement:"), 0L, names, timeline, Map.of(), results, winner);
    }

    private static Optional<SpeedrunTimeline.Entry> entry(ConfigurationSection e) {
        Duration at = Duration.ofMillis(e.getLong("at"));
        UUID who = id(e.getString("who"));
        UUID other = id(e.getString("other"));
        String detail = e.getString("detail", "");
        SpeedrunTimeline.Kind kind = switch (e.getString("kind", "")) {
            case "MILESTONE" -> SpeedrunTimeline.Kind.SPLIT;
            case "CAUGHT" -> SpeedrunTimeline.Kind.CAUGHT;
            case "LIFE_LOST" -> SpeedrunTimeline.Kind.LIFE_LOST;
            case "CAUGHT_AWAY" -> SpeedrunTimeline.Kind.CAUGHT_AWAY;
            case "HUNTER_DIED" -> SpeedrunTimeline.Kind.HUNTER_DIED;
            case "LEFT" -> SpeedrunTimeline.Kind.LEFT;
            case "JOINED" -> SpeedrunTimeline.Kind.JOINED;
            case "SIDE_CHANGED" -> SpeedrunTimeline.Kind.SIDE_CHANGED;
            case "FINISHED" -> SpeedrunTimeline.Kind.FINISH;
            default -> null;   // STARTED: the run's own start needs no entry
        };
        if (kind == null) {
            return Optional.empty();
        }
        if (kind == SpeedrunTimeline.Kind.SPLIT) {
            detail = MILESTONES.getOrDefault(detail, detail);
        } else if (kind == SpeedrunTimeline.Kind.SIDE_CHANGED) {
            detail = "runner".equals(detail) ? "running" : "hunting";
        }
        return Optional.of(new SpeedrunTimeline.Entry(kind, at, who, detail, other));
    }

    private static UUID id(String raw) {
        try {
            return raw == null || raw.isEmpty() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException notOne) {
            return null;
        }
    }

    private static void setAside(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".imported"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException cannot) {
            log.warn("{} was imported but could not be renamed; it will be read again next start, "
                    + "and nothing is added twice.", file);
        }
    }
}
