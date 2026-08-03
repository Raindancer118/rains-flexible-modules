package de.raindancer.modules.claims;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a message asking for a value is given one.
 *
 * <h2>The bug this is about</h2>
 * A player marking a claim corner was told <code>Corner &lt;count&gt; marked.</code> — the placeholder,
 * printed literally, in the middle of a sentence. The wording asked for {@code <count>} and the code supplied
 * {@code index}. Nothing failed: an unfilled placeholder is left as written, so the message went out looking
 * like a bug in the plugin rather than a mismatch between two files.
 *
 * <p>{@link EveryMessageExistsTest} does not catch this. It proves every key the code sends has wording, which
 * is the other half — a key can exist, be found, and still say {@code <count>} to somebody's face.
 *
 * <p>Both halves are checked here: a placeholder nobody supplies, and a value supplied to a message that has
 * nowhere to put it. The second is not cosmetic either — it is usually the same typo seen from the other side.
 */
class PlaceholdersAreFilledTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/modules/claims");
    private static final Path MESSAGES =
            Path.of("src/main/resources/de/raindancer/modules/claims/messages.yml");

    /**
     * MiniMessage's own tags, which look exactly like placeholders and are not.
     *
     * <p>Colours and formats only. Anything with arguments — a gradient, a hover, a click — carries a colon and
     * is excluded by the pattern itself.
     */
    private static final Set<String> FORMATTING = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "italic", "underlined", "strikethrough", "obfuscated", "reset", "newline", "br");

    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z][a-z0-9-]*)>");

    /** A message call: the key, and the names it was given. */
    private record Call(String file, String key, Set<String> supplied) {
    }

    private static Map<String, Object> wording() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(MESSAGES));
        } catch (Exception unreadable) {
            throw new AssertionError("the module's messages.yml does not parse", unreadable);
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten(yaml, "", flat);
        return flat;
    }

    private static void flatten(ConfigurationSection section, String prefix, Map<String, Object> into) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof ConfigurationSection nested) {
                flatten(nested, path, into);
            } else if (value != null) {
                into.put(path, value);
            }
        }
    }

    /** Every send/get call in the module, with the placeholder names it passes. */
    private static List<Call> calls() {
        Pattern sending = Pattern.compile(
                "(?:messages\\(\\)|messages)\\s*\\.\\s*(?:send|sendPlain|get|prefixed|variant|lines)\\s*\\(");
        List<Call> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String body = Files.readString(file);
                Matcher matcher = sending.matcher(body);
                while (matcher.find()) {
                    int open = matcher.end() - 1;
                    int close = matching(body, open);
                    if (close < 0) {
                        continue;
                    }
                    String args = body.substring(open + 1, close);
                    // The key is the first string literal that looks like one; the recipient comes before it.
                    Matcher key = Pattern.compile("\"([a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)+)\"").matcher(args);
                    if (!key.find()) {
                        continue;
                    }
                    Set<String> supplied = new LinkedHashSet<>();
                    Matcher names = Pattern.compile("\"([a-z][a-z0-9-]*)\"\\s*,").matcher(
                            args.substring(key.end()));
                    while (names.find()) {
                        supplied.add(names.group(1));
                    }
                    found.add(new Call(file.getFileName().toString(), key.group(1), supplied));
                }
            }
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module's sources", unreadable);
        }
        return found;
    }

    /** The index of the parenthesis closing the one at {@code open}, or -1. */
    private static int matching(String body, int open) {
        int depth = 0;
        for (int at = open; at < body.length(); at++) {
            char ch = body.charAt(at);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return at;
                }
            }
        }
        return -1;
    }

    private static Set<String> placeholdersIn(Object value) {
        Set<String> wanted = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(String.valueOf(value));
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!FORMATTING.contains(name)) {
                wanted.add(name);
            }
        }
        return wanted;
    }

    @Test
    @DisplayName("the scan found calls and wording, so a refactor cannot quietly empty it")
    void theScanIsNotVacuous() {
        assertThat(calls()).hasSizeGreaterThan(40);
        assertThat(wording()).hasSizeGreaterThan(40);
        assertThat(placeholdersIn("<gray>Corner <white><index></white> marked."))
                .as("the reader has to be able to tell a placeholder from a colour")
                .containsExactly("index");
    }

    @Test
    @DisplayName("every placeholder a message asks for is supplied where it is sent")
    void nothingIsPrintedLiterally() {
        Map<String, Object> wording = wording();
        List<String> unfilled = new ArrayList<>();

        for (Call call : calls()) {
            Object message = wording.get(call.key());
            if (message == null) {
                continue;   // EveryMessageExistsTest owns that half
            }
            for (String wanted : placeholdersIn(message)) {
                if (!call.supplied().contains(wanted)) {
                    unfilled.add(call.file() + " sends " + call.key() + " without <" + wanted + ">");
                }
            }
        }

        assertThat(unfilled)
                .as("an unfilled placeholder is printed as written, in the middle of a sentence, and reads as "
                        + "a bug in the plugin rather than as two files disagreeing")
                .isEmpty();
    }

    @Test
    @DisplayName("nothing is supplied to a message with nowhere to put it")
    void nothingIsSuppliedInVain() {
        Map<String, Object> wording = wording();
        List<String> wasted = new ArrayList<>();

        for (Call call : calls()) {
            Object message = wording.get(call.key());
            if (message == null || call.supplied().isEmpty()) {
                continue;
            }
            Set<String> wanted = placeholdersIn(message);
            for (String given : call.supplied()) {
                if (!wanted.contains(given)) {
                    wasted.add(call.file() + " gives " + call.key() + " a <" + given + "> it never uses");
                }
            }
        }

        assertThat(wasted)
                .as("usually the same typo from the other side: the value is there, the wording asks for a "
                        + "different name, and the player sees the name")
                .isEmpty();
    }
}
