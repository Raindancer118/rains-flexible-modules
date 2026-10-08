package de.raindancer.modules.economy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every key the code sends has a line in messages.yml. A missing one reaches the player as
 * {@code <economy.pay.sent>}, which nothing else would catch.
 */
class MessageKeysTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/modules/economy");
    private static final Path WORDING = Path.of("src/main/resources/de/raindancer/modules/economy/messages.yml");

    /** Keys built at run time: the game's base key with -won or -lost. */
    private static final List<String> BUILT = List.of("economy.gamble.flip", "economy.gamble.dice", "economy.gamble.slots");

    @Test
    @DisplayName("every message the code sends is written down")
    void everyKeyExists() throws IOException {
        Set<String> used = new TreeSet<>();
        Pattern key = Pattern.compile("\"(economy\\.[a-z0-9.-]+)\"");
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher found = key.matcher(Files.readString(file));
                while (found.find()) {
                    used.add(found.group(1));
                }
            }
        }
        for (String base : BUILT) {
            used.remove(base);
            used.add(base + "-won");
            used.add(base + "-lost");
        }
        Set<String> written = keys(Files.readAllLines(WORDING));
        List<String> missing = new ArrayList<>();
        for (String each : used) {
            if (!written.contains(each)) {
                missing.add(each);
            }
        }
        assertThat(used).hasSizeGreaterThan(80);
        assertThat(missing).isEmpty();
    }

    /** Dotted keys out of a plain two-space-indented YAML file. */
    static Set<String> keys(List<String> lines) {
        Set<String> keys = new TreeSet<>();
        List<String> path = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            int depth = (line.length() - line.stripLeading().length()) / 2;
            String name = line.strip().substring(0, line.strip().indexOf(':'));
            while (path.size() > depth) {
                path.removeLast();
            }
            path.add(name);
            if (!line.strip().endsWith(":")) {
                keys.add(String.join(".", path));
            }
        }
        return keys;
    }
}
