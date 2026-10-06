package de.raindancer.modules.cosmetics;

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
 * That what the code sends and what the file says are the same thing.
 *
 * <p>Three ways they come apart, all of them silent:
 *
 * <ul>
 *   <li><b>A key with no wording.</b> {@code Messages} prints the key itself, so a player is told
 *       {@code cosmetics.reloaded} — which reads as a broken plugin rather than a missing line.</li>
 *   <li><b>A placeholder nobody supplies.</b> An unfilled {@code <name>} is printed as written, in the
 *       middle of a sentence. The wording asked for one name and the code passed another; nothing
 *       failed.</li>
 *   <li><b>Wording nothing sends.</b> Harmless on its own, and usually the fossil of a key that was
 *       renamed on one side only — which is the first of these two bugs waiting to happen.</li>
 * </ul>
 *
 * <p>And one that is not silent at all, but was found the hard way, in another module: a bare {@code on}
 * or {@code off} in YAML is a boolean, and a message that has become one prints its own key. Fourteen at
 * once, once.
 */
class MessagesTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/modules/cosmetics");
    private static final Path MESSAGES =
            Path.of("src/main/resources/de/raindancer/modules/cosmetics/messages.yml");

    /**
     * MiniMessage's own tags, which look exactly like placeholders and are not.
     *
     * <p>Colours and formats only. Anything with arguments — a gradient, a hover, a click — carries a
     * colon and is excluded by the pattern itself.
     */
    private static final Set<String> FORMATTING = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "italic", "underlined", "strikethrough", "obfuscated", "reset", "newline", "br");

    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z][a-z0-9-]*)>");

    /** Anything that looks like one of this module's message keys, wherever it appears. */
    private static final Pattern KEY_LITERAL = Pattern.compile("\"(cosmetics(?:\\.[a-z0-9-]+)+)\"");

    /** A message call: the key, and the names it was given. */
    private record Call(String file, String key, Set<String> supplied) {
    }

    // ------------------------------------------------------------------ reading the two sides

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

    private static List<Path> sources() {
        try (Stream<Path> files = Files.walk(SOURCE)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module's sources", unreadable);
        }
    }

    /** Every key named anywhere in the module, including the ones chosen by a switch. */
    private static Set<String> keysNamed() {
        Set<String> keys = new LinkedHashSet<>();
        for (Path file : sources()) {
            Matcher matcher = KEY_LITERAL.matcher(read(file));
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        return keys;
    }

    /**
     * Every send/get call with a literal key, and the placeholder names it passes.
     *
     * <p>The method name catches {@code messages.send(}, {@code messages().send(},
     * {@code services.messages().send(} and {@code live.messages().send(} alike — the pattern matches
     * on {@code messages} or {@code messages()} followed by the dot, wherever it appears in the line,
     * so whatever comes before it (a field, a method call, a receiver) does not matter.
     */
    private static List<Call> calls() {
        Pattern sending = Pattern.compile(
                "(?:messages\\(\\)|messages)\\s*\\.\\s*(?:send|sendPlain|get|prefixed|variant|lines)\\s*\\(");
        List<Call> found = new ArrayList<>();
        for (Path file : sources()) {
            String body = read(file);
            Matcher matcher = sending.matcher(body);
            while (matcher.find()) {
                int open = matcher.end() - 1;
                int close = matching(body, open);
                if (close < 0) {
                    continue;
                }
                String args = body.substring(open + 1, close);
                Matcher key = KEY_LITERAL.matcher(args);
                if (!key.find()) {
                    continue;   // the key came out of a switch or a ternary; keysNamed() has it
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
        return found;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
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

    // ------------------------------------------------------------------ the tests

    @Test
    @DisplayName("the scan found calls and wording, so a refactor cannot quietly empty it")
    void theScanIsNotVacuous() {
        assertThat(wording()).hasSizeGreaterThan(5);
        assertThat(keysNamed()).hasSizeGreaterThan(5);
        assertThat(calls()).isNotEmpty();
        assertThat(placeholdersIn("<gray>Off to <white><name></white> in <white><seconds></white>…"))
                .as("the reader has to be able to tell a placeholder from a colour")
                .containsExactly("name", "seconds");
    }

    @Test
    @DisplayName("every key the code names has wording")
    void everyKeyExists() {
        Map<String, Object> wording = wording();
        List<String> missing = keysNamed().stream()
                .filter(key -> !wording.containsKey(key))
                .toList();

        assertThat(missing)
                .as("a key with no wording is printed as itself, which reads to a player as a broken "
                        + "plugin rather than as a missing line")
                .isEmpty();
    }

    @Test
    @DisplayName("every line in the file is one something sends")
    void nothingIsWrittenInVain() {
        Set<String> named = keysNamed();
        List<String> orphans = wording().keySet().stream()
                .filter(key -> !named.contains(key))
                .toList();

        assertThat(orphans)
                .as("wording nothing sends is usually the fossil of a key renamed on one side only")
                .isEmpty();
    }

    @Test
    @DisplayName("every placeholder a message asks for is supplied where it is sent")
    void nothingIsPrintedLiterally() {
        Map<String, Object> wording = wording();
        List<String> unfilled = new ArrayList<>();

        for (Call call : calls()) {
            Object message = wording.get(call.key());
            if (message == null) {
                continue;   // everyKeyExists owns that half
            }
            for (String wanted : placeholdersIn(message)) {
                if (!call.supplied().contains(wanted)) {
                    unfilled.add(call.file() + " sends " + call.key() + " without <" + wanted + ">");
                }
            }
        }

        assertThat(unfilled)
                .as("an unfilled placeholder is printed as written, mid-sentence, and reads as a bug in "
                        + "the plugin rather than as two files disagreeing")
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
                .as("usually the same typo from the other side: the value is there, the wording asks for "
                        + "a different name, and the player sees the name")
                .isEmpty();
    }

    @Test
    @DisplayName("every refusal asks for nothing but the detail its sender supplies")
    void refusalsAgreeWithTheirSender() {
        // NameStyleService sends verdict.reason() with only "detail" in hand, so the generic scan
        // cannot pair these keys with a call. A refusal that asked for more would print a raw tag.
        Map<String, Object> wording = wording();
        for (String key : List.of("cosmetics.refused.colour", "cosmetics.refused.gradient",
                "cosmetics.refused.any-colour", "cosmetics.refused.decoration",
                "cosmetics.refused.too-many-stops")) {
            assertThat(wording).as("%s is a NameStyleRule refusal", key).containsKey(key);
            assertThat(placeholdersIn(wording.get(key))).as("%s", key).isSubsetOf(Set.of("detail"));
        }
    }

    @Test
    @DisplayName("no message has become a boolean")
    void nothingIsYamlsIdeaOfATruthValue() {
        // A bare on, off, yes or no in YAML is a boolean, and a message that has become one prints its
        // own key to somebody's face. It has happened here, to fourteen keys at once — in another
        // module, not this one, which is exactly why the test travelled with the layout rather than
        // staying behind.
        List<String> notText = new ArrayList<>();
        wording().forEach((key, value) -> {
            if (value instanceof Boolean || value instanceof Number) {
                notText.add(key + " is a " + value.getClass().getSimpleName() + ", not wording");
            }
            if (value instanceof List<?> lines) {
                for (Object line : lines) {
                    if (!(line instanceof String)) {
                        notText.add(key + " has a line that is not text");
                    }
                }
            }
        });
        assertThat(notText).isEmpty();
    }
}
