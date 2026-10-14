package de.raindancer.modules.arena.store;

import de.raindancer.core.data.store.DocumentFolder;
import de.raindancer.core.data.store.YamlStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every block a naval flood changed, with what it was — one document per pit (this server's own, as its worlds are),
 * written before the first drop of water and deleted once the pit is dry again. A server that stops mid-battle finds
 * the document at the next start and puts the pit back exactly as it was.
 */
public final class FloodLedger {

    /** One block: where, and its block data before the flood. */
    public record Change(String world, int x, int y, int z, String before) {
    }

    private static final String SUFFIX = ".yml";
    private static final String LEGACY_SUFFIX = ".flood";
    private static final String KEY = "changes";

    private final DocumentFolder folder;

    public FloodLedger(Path folder) {
        this(DocumentFolder.of(folder), folder);
    }

    /** @param legacy where an arena before 2.0 kept its plain files; each is brought in once, never deleted */
    public FloodLedger(DocumentFolder folder, Path legacy) {
        this.folder = folder;
        importLegacy(legacy);
    }

    /** @return whether it is safely stored; false means the flood must not go ahead */
    public synchronized boolean write(String pit, List<Change> changes) {
        List<String> lines = new ArrayList<>();
        for (Change change : changes) {
            lines.add(change.world() + ' ' + change.x() + ' ' + change.y() + ' ' + change.z() + ' ' + change.before());
        }
        return folder.store(pit + SUFFIX).write(yaml -> yaml.set(KEY, lines));
    }

    public synchronized List<Change> read(String pit) {
        List<Change> changes = new ArrayList<>();
        List<String> lines = folder.store(pit + SUFFIX).readIfReadable()
                .map(yaml -> yaml.getStringList(KEY)).orElse(List.of());
        for (String line : lines) {
            String[] parts = line.split(" ", 5);
            if (parts.length < 5) {
                continue;
            }
            try {
                changes.add(new Change(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]), parts[4]));
            } catch (NumberFormatException damaged) {
                // Skipped; the rest is still put back.
            }
        }
        return changes;
    }

    /** Pits whose flood was never drained — after a crash, or a stop mid-battle. */
    public synchronized List<String> pending() {
        return folder.names(SUFFIX).stream().map(name -> name.substring(0, name.length() - SUFFIX.length())).toList();
    }

    public synchronized void done(String pit) {
        // Left behind, the next start restores the same blocks again — harmless.
        folder.delete(pit + SUFFIX);
    }

    private void importLegacy(Path legacy) {
        if (legacy == null || !Files.isDirectory(legacy)) {
            return;
        }
        try (Stream<Path> files = Files.list(legacy)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(LEGACY_SUFFIX)).toList()) {
                String name = file.getFileName().toString();
                String pit = name.substring(0, name.length() - LEGACY_SUFFIX.length());
                YamlStore store = folder.store(pit + SUFFIX);
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                // What the database already holds is newer than any file: it wins, and the file is only set aside.
                if (store.exists() || store.write(yaml -> yaml.set(KEY, lines))) {
                    Files.move(file, file.resolveSibling(name + ".imported-2.0.0"), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException unreadable) {
            // Left where it is; the next start tries again.
        }
    }
}
