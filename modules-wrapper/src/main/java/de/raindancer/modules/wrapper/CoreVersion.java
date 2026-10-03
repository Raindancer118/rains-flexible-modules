package de.raindancer.modules.wrapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Which RainsCore this jar was compiled against, and whether the running one is older.
 *
 * <p>An older Core does not fail at load: it fails the first time a module calls a method that Core does
 * not have yet, as a {@code NoSuchMethodError} in the middle of a game. Naming both versions at startup
 * is the difference between "put Core 1.41.0 on" and a stack trace.
 */
final class CoreVersion {

    private CoreVersion() {
    }

    static Optional<String> builtAgainst() {
        try (InputStream in = CoreVersion.class.getResourceAsStream("built-against.properties")) {
            if (in == null) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("rainscore.version");
            return version == null || version.startsWith("$") ? Optional.empty() : Optional.of(version);
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    static boolean isOlder(String running, String built) {
        int[] have = parts(running);
        int[] need = parts(built);
        if (have == null || need == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (have[i] != need[i]) {
                return have[i] < need[i];
            }
        }
        return false;
    }

    private static int[] parts(String version) {
        if (version == null) {
            return null;
        }
        String[] split = version.split("[.-]");
        if (split.length < 3) {
            return null;
        }
        int[] numbers = new int[3];
        try {
            for (int i = 0; i < 3; i++) {
                numbers[i] = Integer.parseInt(split[i]);
            }
        } catch (NumberFormatException notAVersion) {
            return null;
        }
        return numbers;
    }
}
