package de.raindancer.modules.arena.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every block a naval flood changed, with what it was — one file per pit, written before the first drop of water and
 * deleted once the pit is dry again. A server that stops mid-battle finds the file at the next start and puts the pit
 * back exactly as it was.
 */
public final class FloodLedger {

    /** One block: where, and its block data before the flood. */
    public record Change(String world, int x, int y, int z, String before) {
    }

    private static final String SUFFIX = ".flood";

    private final Path folder;

    public FloodLedger(Path folder) {
        this.folder = folder;
    }

    /** @return whether it is safely on disk; false means the flood must not go ahead */
    public synchronized boolean write(String pit, List<Change> changes) {
        try {
            Files.createDirectories(folder);
            StringBuilder text = new StringBuilder();
            for (Change change : changes) {
                text.append(change.world()).append(' ').append(change.x()).append(' ').append(change.y()).append(' ')
                        .append(change.z()).append(' ').append(change.before()).append('\n');
            }
            Path writing = folder.resolve(pit + SUFFIX + ".writing");
            Files.writeString(writing, text, StandardCharsets.UTF_8);
            Files.move(writing, folder.resolve(pit + SUFFIX), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException failed) {
            return false;
        }
    }

    public synchronized List<Change> read(String pit) {
        List<Change> changes = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(folder.resolve(pit + SUFFIX), StandardCharsets.UTF_8)) {
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
        } catch (IOException unreadable) {
            return List.of();
        }
        return changes;
    }

    /** Pits whose flood was never drained — after a crash, or a stop mid-battle. */
    public synchronized List<String> pending() {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(SUFFIX))
                    .map(name -> name.substring(0, name.length() - SUFFIX.length())).sorted().toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    public synchronized void done(String pit) {
        try {
            Files.deleteIfExists(folder.resolve(pit + SUFFIX));
        } catch (IOException stuck) {
            // Left for the next start, which restores the same blocks again — harmless.
        }
    }
}
