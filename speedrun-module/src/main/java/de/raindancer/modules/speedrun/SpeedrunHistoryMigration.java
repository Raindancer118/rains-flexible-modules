package de.raindancer.modules.speedrun;

import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.speedrun.manhunt.stats.PlayerStats;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Moves the lobby's own {@code history.yml} — every run up to speedrun 1.28, and the standings beside
 * them — into Core's run history and {@code standings.yml}.
 *
 * <h2>Never anything lost</h2>
 * <ul>
 *   <li>Nothing moves until Core's history has been read from its database and can be written: until
 *       then the old runs are only shown ({@link #show}) and the file is not touched.</li>
 *   <li>A run Core already holds is never replaced; a standing already kept is never replaced.</li>
 *   <li>The original is copied to {@code backup/history.yml} — or a dated name beside it, never over an
 *       older backup — and only set aside (renamed {@code history.yml.imported-2.0.0}) once the copy reads back
 *       byte for byte and everything moved is on disk.</li>
 *   <li>A file that cannot be read is left exactly as it is.</li>
 * </ul>
 */
public final class SpeedrunHistoryMigration {

    private static final LogChannel log = Log.of("speedrun");

    static final String FILE = "history.yml";
    static final String BACKUP_FOLDER = "backup";
    private static final String IMPORTED = ".imported-2.0.0";

    /** What one try came to. */
    public enum Result {
        /** There is no old file. */
        NOTHING,
        /** Moved, backed up, the original renamed. */
        MOVED,
        /** Core's history is not ready yet; try again later. Nothing was changed. */
        WAITING,
        /** The old file cannot be read; it was left alone. */
        LEFT_ALONE
    }

    /** What the old file holds. */
    record Legacy(List<SpeedrunRunRecord> runs, Map<String, Map<UUID, PlayerStats>> standings) {
    }

    private SpeedrunHistoryMigration() {
    }

    /** Shows the old file's runs in {@code history} until they are moved — changes nothing on disk. */
    public static void show(Path folder, SpeedrunHistory history) {
        source(folder).flatMap(SpeedrunHistoryMigration::read).ifPresent(legacy -> history.remember(legacy.runs()));
    }

    /**
     * The old file to move: {@code history.yml} — or, with no backup made yet, the {@code *.imported-2.0.0} that
     * Core 2.0 left when this migration still read the file through a database-backed store, which took it in
     * as a document and set it aside before it could be moved.
     */
    static Optional<Path> source(Path folder) {
        Path file = folder.resolve(FILE);
        if (Files.exists(file)) {
            return Optional.of(file);
        }
        Path setAside = folder.resolve(FILE + IMPORTED);
        return Files.exists(setAside) && !backedUp(folder) ? Optional.of(setAside) : Optional.empty();
    }

    private static boolean backedUp(Path folder) {
        Path backups = folder.resolve(BACKUP_FOLDER);
        if (!Files.isDirectory(backups)) {
            return false;
        }
        try (var kept = Files.list(backups)) {
            return kept.map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.startsWith("history") && name.endsWith(".yml"));
        } catch (IOException unreadable) {
            // Unsure means it may have been moved already: moving twice is what must never happen.
            return true;
        }
    }

    /** One try at moving it. Touches files and Core's database: off the server's threads. */
    public static Result migrate(Path folder, SpeedrunHistory history) {
        Optional<Path> source = source(folder);
        if (source.isEmpty()) {
            return Result.NOTHING;
        }
        Path file = source.get();
        Optional<Legacy> read = read(file);
        if (read.isEmpty()) {
            return Result.LEFT_ALONE;
        }
        if (!history.runs().isLoaded() || !history.runs().isWritable()) {
            history.remember(read.get().runs());
            return Result.WAITING;
        }
        int moved = history.move(read.get().runs());
        boolean standingsKept = true;
        for (Map.Entry<String, Map<UUID, PlayerStats>> mode : read.get().standings().entrySet()) {
            standingsKept &= history.importStandingsNow(mode.getKey(), mode.getValue());
        }
        if (!history.flush() || !standingsKept) {
            log.warn("The old speedrun history could not be written to Core's history yet; {} is kept and "
                    + "will be moved on the next try.", file);
            return Result.WAITING;
        }
        try {
            Path backup = backupOf(folder);
            Files.createDirectories(backup.getParent());
            Files.copy(file, backup);
            if (!Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(backup))) {
                log.warn("The backup {} of the old speedrun history does not match it; the original is kept.", backup);
                return Result.WAITING;
            }
            Path setAside = folder.resolve(FILE + IMPORTED);
            if (!file.equals(setAside)) {
                Files.move(file, setAside, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Moved {} run(s) of the old speedrun history into Core's history; the old file is backed "
                    + "up as {}.", moved, backup);
            history.rerank();
            return Result.MOVED;
        } catch (IOException failed) {
            log.warn("The old speedrun history {} could not be backed up ({}); it is kept as it is.", file,
                    failed.getMessage());
            return Result.WAITING;
        }
    }

    /** {@code backup/history.yml}, or a dated name beside it when that is taken — never over a backup. */
    private static Path backupOf(Path folder) {
        Path backups = folder.resolve(BACKUP_FOLDER);
        Path plain = backups.resolve(FILE);
        if (!Files.exists(plain)) {
            return plain;
        }
        Path dated = backups.resolve("history-" + System.currentTimeMillis() + ".yml");
        for (int n = 1; Files.exists(dated); n++) {
            dated = backups.resolve("history-" + System.currentTimeMillis() + "-" + n + ".yml");
        }
        return dated;
    }

    /** The old file's runs and standings; empty when there is none, or it cannot be read. */
    static Optional<Legacy> read(Path file) {
        // A plain file, never a document: Core's own store would take it into the database and set it aside.
        YamlStore store = new YamlStore(file, null, Documents.Scope.SERVER);
        if (!store.exists()) {
            return Optional.empty();
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            log.warn("The old speedrun history {} cannot be read ({}); it is left exactly as it is.",
                    store.file(), String.join("; ", store.problems()));
            return Optional.empty();
        }
        List<SpeedrunRunRecord> runs = new ArrayList<>();
        ConfigurationSection all = yaml.getConfigurationSection("runs");
        if (all != null) {
            for (String id : all.getKeys(false)) {
                SpeedrunRunRecord.readFrom(id, all.getConfigurationSection(id)).ifPresentOrElse(runs::add,
                        () -> log.warn("Run {} in the old speedrun history could not be read and stays in its backup.", id));
            }
        }
        Map<String, Map<UUID, PlayerStats>> standings = new LinkedHashMap<>();
        ConfigurationSection kept = yaml.getConfigurationSection("standings");
        if (kept != null) {
            for (String mode : kept.getKeys(false)) {
                standings.put(mode, SpeedrunHistory.readStandings(kept.getConfigurationSection(mode)));
            }
        }
        return Optional.of(new Legacy(runs, standings));
    }
}
