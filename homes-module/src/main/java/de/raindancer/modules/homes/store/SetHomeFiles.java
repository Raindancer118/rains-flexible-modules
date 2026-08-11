package de.raindancer.modules.homes.store;

import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where a file that used to belong to the third-party {@code SetHome} plugin actually is, tried in the
 * order a mislaid one is most likely found.
 *
 * <h2>Why this is shared between {@link SetHomePluginFile} and {@link SetHomeConfigFile}</h2>
 * {@code homes.yml} and {@code config.yml} sat in the same folder and are lost, renamed or moved by hand
 * for the same reasons — this is that one reason, written once. Each caller only ever fills in which
 * file it is after {@code SetHome}: the search itself does not know or care whether it is coordinates or
 * settings it is looking for.
 */
final class SetHomeFiles {

    private SetHomeFiles() {
    }

    /**
     * The expected place is {@code <plugins>/SetHome/<fileName>} — a sibling of every plugin's data
     * folder, this module's included. But "expected" is not "guaranteed": the folder can be renamed,
     * cased differently by whatever unzipped it, or an admin migrating by hand can drop the file beside
     * this module's own files instead of hunting for where SetHome used to live. None of that should
     * mean the migration silently does not run — so this tries, in order:
     *
     * <ol>
     *     <li>{@code <plugins>/SetHome/<fileName>} — where the plugin itself always put it;</li>
     *     <li>any folder directly under {@code <plugins>} whose name is {@code SetHome} ignoring case,
     *         holding a file of that name — the folder survived, only its casing did not;</li>
     *     <li>{@code <module data>/SetHome/<fileName>} — the file copied in next to this module's own
     *         files rather than reconstructed under {@code <plugins>};</li>
     *     <li>{@code <module data>/sethome-<fileName>} — the file itself, renamed and dropped straight
     *         into this module's data folder, for an admin doing the migration by hand.</li>
     * </ol>
     *
     * @param fileName        {@code homes.yml} or {@code config.yml}
     * @param pluginsFolder   the server's {@code plugins} folder, or null when it could not be found
     * @param moduleDataFolder this module's own data folder
     * @return the first candidate that is an actual file, or empty when none of them are
     */
    static Optional<Path> locate(String fileName, Path pluginsFolder, Path moduleDataFolder) {
        List<Path> candidates = new ArrayList<>();
        if (pluginsFolder != null) {
            candidates.add(pluginsFolder.resolve("SetHome").resolve(fileName));
        }
        if (moduleDataFolder != null) {
            candidates.add(moduleDataFolder.resolve("SetHome").resolve(fileName));
            candidates.add(moduleDataFolder.resolve("sethome-" + fileName));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return findCaseInsensitive(fileName, pluginsFolder);
    }

    /**
     * A folder called {@code SetHome} in every case but the right one, still under {@code plugins}.
     *
     * <p>Whatever unzipped an export onto a fresh server is exactly the kind of step that lower-cases a
     * folder name without anybody deciding it should. One directory listing settles it either way, and
     * costs nothing on every other boot once the file has been set aside.
     */
    private static Optional<Path> findCaseInsensitive(String fileName, Path pluginsFolder) {
        if (pluginsFolder == null || !Files.isDirectory(pluginsFolder)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(pluginsFolder)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().equalsIgnoreCase("SetHome"))
                    .map(dir -> dir.resolve(fileName))
                    .filter(Files::isRegularFile)
                    .findFirst();
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Renames a SetHome file aside rather than deleting it, once it has been read — kept, not
     * deleted, so a server that has to roll back still has it, and a second start does not read it
     * again.
     */
    static void setAside(Path file, LogChannel log) {
        Path aside = file.resolveSibling(file.getFileName() + ".imported");
        try {
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            if (log != null) {
                log.info("{} is now {} — kept, not deleted, in case this has to be undone.",
                        file.getFileName(), aside.getFileName());
            }
        } catch (Exception couldNotMove) {
            if (log != null) {
                log.warn("Could not set {} aside ({}). It will be read again on the next start — "
                                + "move it by hand.",
                        file, couldNotMove.toString());
            }
        }
    }
}
