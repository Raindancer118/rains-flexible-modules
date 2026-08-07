package de.raindancer.modules.hungergames.service;

import de.raindancer.core.platform.log.LogChannel;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /fliptable confirm} — the whole server back to nothing, worlds and all.
 *
 * <h2>What it is for</h2>
 * A tournament leaves an arena carved into the world, chests full of a round that already happened, and a
 * session file describing forty people who are finished. The next tournament wants none of it. Doing that by
 * hand means stopping the server, deleting three folders and four files in the right order, and starting it
 * again — which is a thing somebody does wrong at eleven at night, once.
 *
 * <h2>Why the deletion happens in a shutdown hook, which looks mad and is not</h2>
 * Deleting a world folder while the server is running does not work. Paper writes parts of it back as it
 * shuts down — {@code level.dat}, region files, {@code session.lock} — and what is left is a half world that
 * Paper migrates on the next start and then gives up on with {@code IllegalStateException: Overworld settings
 * missing}, because {@code world_gen_settings.dat} is not there. The server does not come back up.
 *
 * <p>So the deletion is registered as a JVM shutdown hook and runs after Paper has saved and closed
 * everything. At that point no plugin is loaded and there is no Bukkit API left, which is why this half
 * reports through the log channel it was handed rather than through anything on the server.
 *
 * <h2>The three things this refuses to do</h2>
 * <ul>
 *   <li><b>It never deletes a folder it was not given by the running server.</b> The world folders come from
 *       {@code World.getWorldFolder()}, read <em>before</em> the shutdown, rather than from a name joined
 *       onto the world container. Paper 26 puts worlds in more than one layout, and a name-based guess that
 *       misses is a delete that silently does nothing — or, far worse, one that hits the wrong folder.</li>
 *   <li><b>It never leaves the arena's configuration behind.</b> {@code config.yml}, {@code loot.yml},
 *       {@code gamemasters.yml} and the schematics survive on purpose: they are the evening's tuning, not
 *       the round's state. Only the four state files go, exactly as the source chose them.</li>
 *   <li><b>It never runs twice.</b> Arming is one-way; a second {@code /fliptable confirm} says the reset is
 *       already coming rather than adding a second shutdown hook.</li>
 * </ul>
 */
public final class FliptableService {

    /**
     * The files holding a round's state, and nothing else.
     *
     * <p>The source's own list, kept exactly. What is <em>not</em> here matters more than what is:
     * {@code config.yml}, {@code loot.yml}, {@code gamemasters.yml} and the schematics are the tuning
     * somebody spent evenings on, and a reset that took them would be one nobody dares run.
     */
    public static final List<String> STATE_FILES =
            List.of("whitelist.yml", "teams.yml", "session.yml", "runtime.yml");

    /**
     * Files that must survive, named so the rule is checkable rather than implied.
     *
     * <p>{@link #wouldDelete} is asserted against this in a test: the interesting property of this class is
     * not what it deletes but what it leaves, and that property is invisible in a diff that forgets it.
     */
    public static final List<String> KEPT_FILES =
            List.of("config.yml", "loot.yml", "gamemasters.yml", "tributes.yml", "arena.yml");

    /** How long a world's {@code session.lock} is waited on before deleting anyway. */
    public static final long UNLOCK_TIMEOUT_MILLIS = 30_000L;

    /** The word that has to be typed. Nothing else counts, including "yes", "y" or "please". */
    public static final String CONFIRMATION = "confirm";

    /**
     * Whether a reset is already coming.
     *
     * <p>Static and one-way, because the thing it protects is process-wide: a second
     * {@code /fliptable confirm} typed while the first is still counting down would add a second shutdown
     * hook over the same folders, and two threads walking one directory tree in opposite orders is how a
     * delete half-finishes and reports success.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean ARMED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private FliptableService() {
    }

    /** Whether {@link #armFor} has already run. */
    public static boolean isArmed() {
        return ARMED.get();
    }

    /**
     * Whether what was typed is the confirmation.
     *
     * <p>Its own method, and case-insensitive but otherwise exact, because this is the single guard between a
     * mistyped command and a deleted server. A prefix match — anything starting with "c" — is the shape of
     * that guard failing.
     */
    public static boolean isConfirmed(String[] args) {
        return args != null && args.length > 0 && args[0] != null
                && args[0].strip().toLowerCase(Locale.ROOT).equals(CONFIRMATION);
    }

    /**
     * Exactly what a reset would remove, given the world folders and the module's data folder.
     *
     * <p>Pure, so the blast radius can be read in a test rather than discovered on a server. Anything not on
     * this list survives — that is the whole contract, and the reason this is a list rather than a loop
     * buried inside the deletion.
     */
    public static List<Path> wouldDelete(List<Path> worldFolders, Path dataFolder) {
        List<Path> doomed = new ArrayList<>(outermost(worldFolders));
        if (dataFolder != null) {
            for (String file : STATE_FILES) {
                doomed.add(dataFolder.resolve(file));
            }
        }
        return List.copyOf(doomed);
    }

    /**
     * The world folders with duplicates and nested ones removed — the outer folder only.
     *
     * <p>Paper 26 does not put every world beside the others. The overworld is {@code <level-name>}, and a
     * world created afterwards lives at {@code <level-name>/dimensions/<namespace>/<name>} — inside it. Handed
     * both, a naive loop deletes the outer folder and then walks into a path that no longer exists, which is
     * survivable, and reports a failure for it, which is not: the one line somebody reads after a reset would
     * say the world could not be removed when it was the first thing to go.
     *
     * <p>Comparing absolute, normalised paths, because {@code World.getWorldFolder()} answers relative to the
     * working directory on some layouts and absolutely on others, and {@code "world"} and
     * {@code "./world"} are not the same string.
     */
    static List<Path> outermost(List<Path> folders) {
        if (folders == null) {
            return List.of();
        }
        List<Path> normalised = folders.stream()
                .filter(java.util.Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();

        return normalised.stream()
                .filter(path -> normalised.stream()
                        .noneMatch(other -> !other.equals(path) && path.startsWith(other)))
                .toList();
    }

    /**
     * Registers the shutdown hook that does it.
     *
     * <p>The folders are handed in as they are now, because after the shutdown starts there is no server left
     * to ask. That is not an implementation detail: it is the reason this takes paths rather than names.
     *
     * @return whether this call armed it. {@code false} means a reset was already coming and nothing was
     *         added — the caller says so rather than registering a second hook over the same folders
     */
    public static boolean armFor(List<Path> worldFolders, Path dataFolder, LogChannel log) {
        if (!ARMED.compareAndSet(false, true)) {
            return false;
        }
        List<Path> doomed = wouldDelete(worldFolders, dataFolder);
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> deleteEverything(doomed), "hungergames-fliptable"));
        log.warn("Fliptable armed: {} path(s) will be deleted once the server has shut down and released "
                + "them. The configuration, the loot tables and the schematics are not among them.",
                doomed.size());
        doomed.forEach(path -> log.warn("  will be deleted: {}", path));
        return true;
    }

    /**
     * Runs in the shutdown hook. No Bukkit, no logger that still has a file open — {@link System#out} only.
     */
    static void deleteEverything(List<Path> doomed) {
        say("(╯°□°)╯︵ ┻━┻  deleting the worlds and the round's state …");
        for (Path path : doomed) {
            if (!Files.exists(path)) {
                continue;
            }
            if (Files.isDirectory(path) && !waitUntilClosed(path)) {
                say("WARNING: " + path.getFileName() + " was still locked after "
                        + (UNLOCK_TIMEOUT_MILLIS / 1000) + "s — deleting it anyway.");
            }
            if (deleteTree(path)) {
                say("deleted: " + path);
            } else {
                say("FAILED to delete " + path + " — remove it by hand, or the server will not start "
                        + "cleanly.");
            }
        }
        say("Fliptable done. The worlds are generated fresh on the next start.");
    }

    /**
     * Waits for Paper to let go of a world's {@code session.lock}.
     *
     * <p>Without this the delete races the shutdown it was scheduled after: the hook can run while the last
     * region file is still being written, and the result is the half-world this whole design exists to
     * avoid.
     */
    private static boolean waitUntilClosed(Path worldFolder) {
        Path lock = worldFolder.resolve("session.lock");
        long deadline = System.currentTimeMillis() + UNLOCK_TIMEOUT_MILLIS;
        while (true) {
            if (!Files.exists(lock) || isUnlocked(lock)) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Whether that lock file can be taken, which is how "the world is closed" is actually asked. */
    private static boolean isUnlocked(Path sessionLock) {
        try (RandomAccessFile file = new RandomAccessFile(sessionLock.toFile(), "rw");
             FileChannel channel = file.getChannel()) {
            FileLock taken = channel.tryLock();
            if (taken == null) {
                return false;
            }
            taken.release();
            return true;
        } catch (IOException | RuntimeException cannotTell) {
            // Cannot tell, so assume not. Waiting a little longer costs seconds; deleting a world that is
            // still open costs the server.
            return false;
        }
    }

    /**
     * Depth-first, so a directory is removed after everything in it.
     *
     * <h2>Why this walks the tree by hand rather than through {@code Files.walk}</h2>
     * {@code Files.walk} is one lazy stream, and an {@code IOException} reading <em>any</em> directory in
     * it — a region file mid-write, a permission bit, anything — propagates out of the terminal operation
     * and abandons the walk right there. Everything visited before that point is already deleted; nothing
     * after it is even looked at. The result is exactly the half-deleted world this whole class exists to
     * avoid: {@code level.dat} gone, an empty {@code dimensions/minecraft/} left behind, and the server
     * refusing to boot with "Overworld settings missing" — which is precisely what happened on this
     * server's own {@code world/} folder the first time this ran into one such entry.
     *
     * <p>{@link Files#walkFileTree} with a visitor does not have that failure mode: {@code visitFileFailed}
     * is called <em>per entry</em>, so one unreadable file is skipped and the walk continues into every
     * sibling and every directory after it. A tree that cannot be fully deleted still ends up as empty as
     * it can be made, rather than abandoned at the first obstacle.
     */
    private static boolean deleteTree(Path root) {
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    deleteQuietly(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException unreadable) {
                    // Skipped rather than aborting the whole tree — see the class note above.
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException failure) {
                    deleteQuietly(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException cannotEvenStart) {
            return false;
        }
        return !Files.exists(root);
    }

    /** One path, gone if it can be — never a reason for the rest of the tree to stop being deleted. */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Reported by the existence check the caller makes afterwards, rather than per file: forty
            // lines about one locked region file is not more useful than "this folder is still there".
        }
    }

    private static void say(String line) {
        System.out.println("[HungerGames] " + line);
    }
}
