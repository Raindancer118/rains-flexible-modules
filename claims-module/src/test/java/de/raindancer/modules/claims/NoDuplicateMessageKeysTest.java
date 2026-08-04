package de.raindancer.modules.claims;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That no key in {@code messages.yml} is declared twice.
 *
 * <h2>Why the other message tests cannot catch this</h2>
 * Because they all read the file through {@code YamlConfiguration}, and parsing is precisely what
 * destroys the evidence: SnakeYAML keeps the <em>last</em> of two keys at the same path and throws the
 * first away. By the time {@code EveryMessageExistsTest} looks at the map, the duplicate is gone and
 * everything it checks passes — the key exists, its placeholders are supplied, nothing is orphaned.
 *
 * <p>So this reads the raw text instead, and tracks indentation to work out the full path of each key
 * the way YAML itself would. Anything else — a plain scan for repeated key names — would report the
 * two legitimate {@code stick-given} lines that live under different parents.
 *
 * <h2>What it actually costs</h2>
 * Two things, and the second is the one that matters. The server logs
 * {@code duplicate keys found : saved} on every single boot, which teaches everybody reading the log to
 * skim past warnings. And the losing line is dead wording that still <em>looks</em> live: somebody
 * fixing a typo has an even chance of editing the line nobody sees, watching the server, and concluding
 * the wording is cached somewhere.
 *
 * <p>Found on a live server, in the {@code admin:} section, twice over — {@code admin.saved} and
 * {@code admin.stick-given} — after the "restored operational surface" block re-declared keys the file
 * already had further up.
 */
class NoDuplicateMessageKeysTest {

    private static final Path MESSAGES =
            Path.of("src/main/resources/de/raindancer/modules/claims/messages.yml");

    /** A mapping key at the start of a line: its indentation, and its name. */
    private static final Pattern KEY = Pattern.compile("^(\\s*)([A-Za-z0-9_-]+):");

    /** One key that was declared more than once, and where. */
    private record Duplicate(String path, int firstSeenAt, int againAt) {

        @Override
        public String toString() {
            return path + " (line " + firstSeenAt + ", again at " + againAt + ")";
        }
    }

    private static List<String> lines() {
        try {
            return Files.readAllLines(MESSAGES);
        } catch (IOException unreadable) {
            throw new AssertionError("the module's messages.yml could not be read", unreadable);
        }
    }

    /**
     * Every key declared twice under the same parent.
     *
     * <p>The indentation stack is what makes this a path rather than a name: two keys called
     * {@code stick-given} are only a duplicate when they sit under the same parent, and this file has
     * a legitimate pair that does not.
     */
    private static List<Duplicate> duplicates() {
        List<Duplicate> found = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<String> open = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();

        int number = 0;
        for (String line : lines()) {
            number++;
            String trimmed = line.strip();
            // Blank lines, comments and list entries are not keys. A list entry in particular starts
            // with "- " and can carry a colon inside its text.
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) {
                continue;
            }
            Matcher matcher = KEY.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            int indent = matcher.group(1).length();
            String key = matcher.group(2);

            // Anything indented at least as far as this key is a sibling or deeper, so it is closed.
            while (!indents.isEmpty() && indents.getLast() >= indent) {
                indents.removeLast();
                open.removeLast();
            }
            String path = open.isEmpty() ? key : String.join(".", open) + "." + key;

            Integer before = seen.put(path, number);
            if (before != null) {
                found.add(new Duplicate(path, before, number));
            }
            indents.add(indent);
            open.add(key);
        }
        return found;
    }

    @Test
    @DisplayName("the scan reads the file and understands nesting, so it cannot pass by seeing nothing")
    void theScanIsNotVacuous() {
        assertThat(lines())
                .as("messages.yml is empty or gone")
                .hasSizeGreaterThan(100);

        // The pair this file legitimately has: one under the player's section and one under admin's.
        // A scan that called these a duplicate would be a scan somebody has to switch off.
        long stickGiven = lines().stream()
                .filter(line -> line.strip().startsWith("stick-given:"))
                .count();
        assertThat(stickGiven)
                .as("two keys of the same name under different parents are not a duplicate, and this "
                        + "file is what proves the scan knows the difference")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("no key is declared twice under the same parent")
    void nothingIsDeclaredTwice() {
        assertThat(duplicates())
                .as("the second of these wins and the first is dead wording that still looks live — "
                        + "so somebody fixing a typo has an even chance of editing the line nobody "
                        + "sees. The server also says so on every boot, which teaches everybody to "
                        + "skim past its warnings")
                .isEmpty();
    }
}
