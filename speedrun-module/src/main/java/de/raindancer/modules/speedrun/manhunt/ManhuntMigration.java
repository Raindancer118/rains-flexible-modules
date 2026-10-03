package de.raindancer.modules.speedrun.manhunt;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manhunt's files, carried over from where the separate plugin kept them into the speedrun plugin's
 * folder — once, on the first start of the plugin that has Manhunt built in.
 *
 * <h2>Where they were</h2>
 * Beside this folder, under the old plugin's name: {@code plugins/RainsManhunt/} next to
 * {@code plugins/RainsSpeedrun/} for the standalone jars, {@code plugins/RainsSpeedrunServer/modules/manhunt/}
 * next to {@code …/modules/speedrun/} for the bundle. Its settings were the module's {@code config.yml};
 * here they are {@code manhunt.yml}, beside the lobby's own.
 *
 * <h2>Nothing is lost on the way</h2>
 * A file already in the new folder is never overwritten — the old one stays where it was and the log
 * says so. Every file that does move is copied first, then copied again into
 * {@code backup/<old folder>/}, and only once both copies read back identical is the original
 * removed. A note is left in the old folder saying where everything went.
 */
public final class ManhuntMigration {

    private static final LogChannel log = Log.of("speedrun");

    /** Where, inside the speedrun folder, the originals are kept. */
    public static final String BACKUP_FOLDER = "backup";
    /** The note left in the old folder. */
    public static final String NOTE = "MOVED-TO-RAINSSPEEDRUN.txt";

    /** The old folder names, beside this one: the bundle's module folder, then the standalone plugin's. */
    static final List<String> OLD_FOLDERS = List.of("manhunt", "RainsManhunt");

    /** Old name to new name, every file Manhunt ever wrote. */
    static final Map<String, String> FILES = files();

    private static Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("config.yml", ManhuntGame.SETTINGS_FILE);
        files.put("stats.yml", "stats.yml");
        files.put("hunts.yml", "hunts.yml");
        files.put("whitelist-state.yml", "whitelist-state.yml");
        files.put("whitelist-vips.yml", "whitelist-vips.yml");
        files.put("setup.yml", "setup-manhunt.yml");
        return files;
    }

    /**
     * @param from  the old folders that held anything
     * @param moved the new files that now hold what the old ones did
     * @param kept  old files left where they were, because their new name was already taken
     */
    public record Result(List<Path> from, List<Path> moved, List<Path> kept) {
    }

    private ManhuntMigration() {
    }

    /** Moves whatever an old Manhunt left beside {@code speedrunFolder} into it. */
    public static Result run(Path speedrunFolder) {
        List<Path> from = new ArrayList<>();
        List<Path> moved = new ArrayList<>();
        List<Path> kept = new ArrayList<>();
        Path parent = speedrunFolder.toAbsolutePath().getParent();
        if (parent == null) {
            return new Result(from, moved, kept);
        }
        for (String name : OLD_FOLDERS) {
            Path old = parent.resolve(name);
            if (!Files.isDirectory(old) || old.equals(speedrunFolder.toAbsolutePath())) {
                continue;
            }
            boolean any = false;
            for (Map.Entry<String, String> file : FILES.entrySet()) {
                Path source = old.resolve(file.getKey());
                if (!Files.isRegularFile(source)) {
                    continue;
                }
                any = true;
                Path target = speedrunFolder.resolve(file.getValue());
                if (Files.exists(target)) {
                    kept.add(source);
                    log.warn("{} was not carried over: {} already exists. The old file is still where it was.",
                            source, target);
                    continue;
                }
                try {
                    carry(source, target, speedrunFolder.resolve(BACKUP_FOLDER).resolve(name).resolve(file.getKey()));
                    moved.add(target);
                } catch (IOException failed) {
                    kept.add(source);
                    log.error(failed, "{} could not be carried over to {}; it is still where it was.", source, target);
                }
            }
            if (any) {
                from.add(old);
                leaveANote(old, speedrunFolder);
            }
        }
        if (!moved.isEmpty()) {
            log.info("Manhunt's data was carried over from the old plugin: {} file(s), originals kept in {}.",
                    moved.size(), speedrunFolder.resolve(BACKUP_FOLDER));
        }
        return new Result(from, moved, kept);
    }

    /** Copy, back up, check both, and only then remove the original. */
    private static void carry(Path source, Path target, Path backup) throws IOException {
        Files.createDirectories(target.getParent());
        Files.createDirectories(backup.getParent());
        byte[] original = Files.readAllBytes(source);
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        if (!Files.exists(backup)) {
            Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
        if (!Arrays.equals(original, Files.readAllBytes(target)) || !Arrays.equals(original, Files.readAllBytes(backup))) {
            Files.deleteIfExists(target);
            throw new IOException("the copy did not read back the same");
        }
        Files.delete(source);
    }

    private static void leaveANote(Path old, Path speedrunFolder) {
        try {
            Files.writeString(old.resolve(NOTE), "Manhunt is part of RainsSpeedrun now. Its files were moved to "
                    + speedrunFolder.toAbsolutePath() + " on " + LocalDateTime.now() + "; the originals are in "
                    + speedrunFolder.resolve(BACKUP_FOLDER).toAbsolutePath() + ".\n");
        } catch (IOException cannot) {
            log.warn("Could not leave a note in {} about where Manhunt's files went.", old);
        }
    }
}
