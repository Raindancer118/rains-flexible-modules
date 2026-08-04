package de.raindancer.modules.tpa;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.raindancer.core.data.settings.Key;
import de.raindancer.modules.tpa.store.TpaPrefsFile;
import de.raindancer.modules.tpa.util.PermissionNodes;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
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
 * <h2>The problem this module has that the others do not</h2>
 * Its message keys, its config keys and its permission nodes all begin {@code homes.} — because the
 * config paths and the nodes are the old plugin's and must stay exactly as they were, or an upgrading
 * server silently loses its settings and its granted limits. So a scan for "anything shaped like a
 * message key" finds {@code homes.max} and {@code homes.bypass.warmup} too, and demands wording for
 * them.
 *
 * <p>The exclusions are therefore <b>derived, never listed</b>: the config paths are read off
 * {@code TpaSettings}' own {@code @Key} annotations and the nodes off {@code PermissionNodes}. A
 * hand-written list would be right on the day it was written and wrong the first time somebody added a
 * setting — and the failure would be a test demanding wording for a config path, which is exactly the
 * sort of thing that gets "fixed" by inventing a message nobody sends.
 *
 * <p>Three ways they come apart, all of them silent:
 *
 * <ul>
 *   <li><b>A key with no wording.</b> {@code Messages} prints the key itself, so a player is told
 *       {@code homes.deleted} — which reads as a broken plugin rather than a missing line.</li>
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

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/modules/tpa");
    private static final Path MESSAGES =
            Path.of("src/main/resources/de/raindancer/modules/tpa/messages.yml");

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
    private static final Pattern KEY_LITERAL = Pattern.compile("\"(tpa(?:\\.[a-z0-9-]+)+)\"");

    /**
     * Everything that is spelled like a message key and is not one.
     *
     * <p>Read from the schema and the nodes themselves, so adding a setting or a permission cannot
     * break this test — see the class note.
     */
    private static Set<String> notMessages() {
        Set<String> keys = new LinkedHashSet<>();
        for (RecordComponent component : TpaSettings.class.getRecordComponents()) {
            Key key = component.getAnnotation(Key.class);
            if (key != null) {
                keys.add(key.value());
            }
        }
        keys.add(PermissionNodes.USE);
        keys.add(PermissionNodes.BACK);
        keys.add(PermissionNodes.BYPASS_WARMUP);
        keys.add(PermissionNodes.BYPASS_COOLDOWN);
        keys.add(PermissionNodes.BYPASS_TOGGLE);
        // The block-list file, which the module reads as the old plugin left it. A filename, not a
        // key, and the one literal here that is neither a setting nor a node.
        keys.add(TpaPrefsFile.FILE_NAME);
        return keys;
    }

    /** Whether this literal is a message key at all. */
    private static boolean isAMessage(String key) {
        return !notMessages().contains(key);
    }

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
                if (isAMessage(matcher.group(1))) {
                    keys.add(matcher.group(1));
                }
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
    @DisplayName("the travel-outcome lines ask for no more than the one thing their caller supplies")
    void theTravelOutcomeLinesAgreeWithTheirCaller() {
        // Their key is chosen by the switch in TpaRequestService.keyFor(TravelReason) — and again in
        // BackService's own — so the generic scan cannot pair them with a call site: keyFor(why) is a
        // method call, not a literal, and calls() walks straight past it.
        //
        // Each of those call sites supplies exactly one thing, and some of the wordings choose not to
        // use it: "You moved, so you are staying put." does not need to repeat who they were going to.
        // That is wording, not a bug. What would print an unfilled tag mid-sentence is a line asking
        // for MORE than the call gives it, so that is what is asserted.
        Map<String, Object> wording = wording();
        for (String key : List.of("tpa.cancelled.moved", "tpa.cancelled.hurt",
                "tpa.already-travelling", "tpa.world-gone", "tpa.nowhere-safe",
                "tpa.could-not-check", "tpa.teleport-refused", "tpa.cannot-schedule")) {
            assertThat(wording).as("%s is one of TravelReason's eight outcomes", key).containsKey(key);
            assertThat(placeholdersIn(wording.get(key)))
                    .as("%s is sent from TpaRequestService with only <player> in hand", key)
                    .isSubsetOf(Set.of("player"));
        }
        for (String key : List.of("tpa.back-cancelled.moved", "tpa.back-cancelled.hurt",
                "tpa.back-world-gone", "tpa.back-nowhere-safe")) {
            assertThat(wording).as("%s is one of BackService's outcomes", key).containsKey(key);
            assertThat(placeholdersIn(wording.get(key)))
                    .as("%s is sent from BackService with only <what> in hand", key)
                    .isSubsetOf(Set.of("what"));
        }
    }

    @Test
    @DisplayName("the two ways of being asked agree with their one caller")
    void theTwoAskedLinesAgreeWithTheirCaller() {
        // These share a call site too — TpaRequestService.ask chooses between them with a ternary, so
        // the key is not a literal the scan can pair with the arguments. That one call supplies the
        // asker's name and how long the request stands, and both wordings need both: somebody who is
        // told they have been asked but not for how long has to guess whether it is worth answering.
        Map<String, Object> wording = wording();
        for (String key : List.of("tpa.asked-you-to", "tpa.asked-you-here")) {
            assertThat(wording).as("%s is one of the two directions", key).containsKey(key);
            assertThat(placeholdersIn(wording.get(key)))
                    .as("%s is sent from TpaRequestService with exactly player and seconds", key)
                    .containsExactlyInAnyOrder("player", "seconds");
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
