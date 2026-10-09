package de.raindancer.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The few things every scenario agrees on: the game and Paper build a run is played on, and where a
 * run keeps its servers and its logs — {@code $RAINS_E2E_OUT}, or {@code target/e2e} of the module
 * running it, which is what CI uploads.
 */
public final class E2e {

    public static final String PAPER_VERSION = "26.3";
    /** 26.3 has no stable build yet; 169 is the BETA Lilly's SMP runs. */
    public static final int PAPER_BUILD = 169;
    public static final String MINECRAFT_VERSION = "26.3";
    /**
     * Folia of the same game version — the bots speak exactly one protocol. Folia 26.3 is not out yet
     * (PaperMC/Folia#507); until it is, {@code -De2e.server=folia} fails at the download, saying so.
     */
    public static final int FOLIA_BUILD = 1;

    private E2e() {
    }

    /**
     * Whether this run plays on Folia rather than Paper — {@code -De2e.server=folia} or
     * {@code RAINS_E2E_SERVER=folia}. The same scenarios, so a plugin that claims Folia is held to it.
     */
    public static boolean onFolia() {
        String chosen = System.getProperty("e2e.server", System.getenv("RAINS_E2E_SERVER"));
        return "folia".equalsIgnoreCase(chosen == null ? "" : chosen.trim());
    }

    public static Path out() {
        String configured = System.getenv("RAINS_E2E_OUT");
        Path out = configured == null || configured.isBlank() ? Path.of("target", "e2e") : Path.of(configured);
        return created(out.toAbsolutePath());
    }

    /** A fresh server folder for {@code scenario}. */
    public static Path serverFolder(String scenario) {
        Path folder = out().resolve("servers").resolve(scenario);
        if (Files.exists(folder)) {
            deleteTree(folder);
        }
        return created(folder);
    }

    /** Where {@code scenario}'s logs are copied to. */
    public static Path logsFolder(String scenario) {
        return created(out().resolve("logs").resolve(scenario));
    }

    private static Path created(Path folder) {
        try {
            return Files.createDirectories(folder);
        } catch (IOException cannot) {
            throw new UncheckedIOException(cannot);
        }
    }

    /** Only ever a folder this harness made under {@link #out()} — a test server of a previous run. */
    private static void deleteTree(Path folder) {
        if (!folder.toAbsolutePath().startsWith(out())) {
            throw new IllegalArgumentException("not an e2e folder: " + folder);
        }
        try (var paths = Files.walk(folder)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException cannot) {
            throw new UncheckedIOException(cannot);
        }
    }
}
