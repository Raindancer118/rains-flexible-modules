package de.raindancer.modules.api;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every module's wording has to be true of, written once.
 *
 * <h2>Why this is an interface</h2>
 * Because these rules were three copies, and three copies of a rule is a rule that is true in two of
 * them by March. JUnit runs {@code @Test} on a {@code default} method for any class implementing the
 * interface, so a module gets the whole set by saying where its sources and its wording are:
 *
 * <pre>
 * class WordingTest implements WordingContract {
 *     public Path moduleSource()  { return Path.of("src/main/java/de/raindancer/modules/names"); }
 *     public Path messagesFile()  { return Path.of("src/main/resources/.../messages.yml"); }
 * }
 * </pre>
 *
 * <p>A module added next year gets them by implementing this, which is the point: the failure these
 * catch is invisible everywhere except in front of a player, so it must not be something anybody has
 * to remember to check for.
 *
 * <h2>The two failures, which are mirrors of each other</h2>
 * Both end with somebody reading a tag instead of seeing a colour, and neither fails a build, logs a
 * line or throws.
 *
 * <ul>
 *   <li><b>A closing tag that closes nothing</b>, in the wording file. Walking into a claim said
 *       {@code You are entering Home, owned by Raindancer118</gray>.} — MiniMessage keeps an
 *       unmatched close exactly as written. Worse, it only misbehaves once nothing is open, so half a
 *       line behaves correctly and the whole thing survives being read.</li>
 *   <li><b>An opening tag that is never parsed</b>, from Java. {@code /history} said
 *       {@code (9 hours ago, for ever) <green>lifted}, because the state was a string of markup put
 *       through {@code Chat.arg} — which escapes what it is given, always, and rightly, since nearly
 *       everything through it is text a player typed.</li>
 * </ul>
 *
 * <h2>Why the first asks the real parser</h2>
 * The rule is not "the tags balance" — plenty of lines deliberately never close anything, and
 * MiniMessage is happy with that. The rule is that <b>no player is ever shown a tag</b>, and the only
 * thing that knows what a player is shown is MiniMessage. So each line is rendered and flattened to
 * the text a client would draw, and that must contain no markup. It therefore cannot drift from the
 * parser either: an Adventure release that changed how an unmatched tag is handled would change this
 * test's answer along with the game's.
 */
public interface WordingContract {

    /** The module's own package — {@code src/main/java/de/raindancer/modules/<name>}. */
    Path moduleSource();

    /** The module's bundled {@code messages.yml}. */
    Path messagesFile();

    /** How many lines of wording the file has to have, so a broken path cannot pass silently. */
    default int fewestWordingLines() {
        return 5;
    }

    /** How many source files the module has to have, for the same reason. */
    default int fewestSourceFiles() {
        return 10;
    }

    MiniMessage MINI = MiniMessage.miniMessage();

    /** The colour and format tags. A {@code <player>} is a placeholder, not markup. */
    Set<String> FORMATS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
            "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "italic", "underlined", "strikethrough", "obfuscated");

    Pattern TAG = Pattern.compile("<(/?)([a-z_]+)>");

    Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * A placeholder standing where a tag name goes: {@code <<colour>>…</<colour>>}.
     *
     * <p>A real pattern, not a mistake — the colour is chosen when the line is sent, so a rank renders
     * in its own colour. Filled in before the line is judged, because unfilled it is two unknown tags
     * and the closing one reads exactly like the bug this hunts.
     */
    Pattern TAG_FROM_A_PLACEHOLDER = Pattern.compile("<(/?)<([a-z][a-z0-9-]*)>>");

    // ───────────────────────────────────────────────────────────── the wording file

    @Test
    @DisplayName("the scan reads the wording and the sources, so it cannot pass by looking at nothing")
    default void theScanIsNotVacuous() {
        assertThat(wordingLines()).hasSizeGreaterThanOrEqualTo(fewestWordingLines());
        assertThat(sources()).hasSizeGreaterThanOrEqualTo(fewestSourceFiles());

        // And it can tell the two apart. Verbatim, because it also shows why the bug survived a
        // reading: MiniMessage consumes an unmatched close while a matching tag is still open, so the
        // first </gray> here vanishes quietly and only the second reaches the player.
        assertThat(asShown("<gray>Entering <white><claim></gray>, owned by <white><owner></gray>."))
                .contains("</gray>");
        assertThat(asShown("<gray>Entering <white><claim></white>, owned by <white><owner></white>."))
                .doesNotContain("</");
        assertThat(isMarkup(" <green>lifted")).isTrue();
        assertThat(isMarkup("<player> was banned")).as("a placeholder is not markup").isFalse();
    }

    @Test
    @DisplayName("no message shows a tag to the player")
    default void nothingLeaksItsOwnMarkup() {
        List<String> leaking = new ArrayList<>();
        for (Map.Entry<String, String> line : wordingLines()) {
            String shown = asShown(line.getValue());
            if (shown.contains("</")) {
                leaking.add(line.getKey() + " renders as: " + shown);
            }
        }
        assertThat(leaking)
                .as("a closing tag that closes nothing is printed to the player exactly as written, "
                        + "mid-sentence — nothing fails and nothing is logged, and the plugin simply "
                        + "looks broken")
                .isEmpty();
    }

    @Test
    @DisplayName("no message is unparseable")
    default void everyMessageParses() {
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> line : wordingLines()) {
            try {
                MINI.deserialize(filled(line.getValue()));
            } catch (RuntimeException unparseable) {
                broken.add(line.getKey() + ": " + unparseable.getMessage());
            }
        }
        assertThat(broken).isEmpty();
    }

    @Test
    @DisplayName("no message has become a boolean")
    default void nothingIsYamlsIdeaOfATruthValue() {
        // A bare on, off, yes or no in YAML is a boolean, and a message that has become one prints
        // its own key to somebody's face. It has happened here, to fourteen keys at once.
        List<String> notText = new ArrayList<>();
        wording().forEach((key, value) -> {
            if (value instanceof Boolean || value instanceof Number) {
                notText.add(key + " is a " + value.getClass().getSimpleName() + ", not wording");
            }
        });
        assertThat(notText).isEmpty();
    }

    // ───────────────────────────────────────────────────────────── markup from Java

    @Test
    @DisplayName("no colour is written straight into a placeholder that escapes it")
    default void noMarkupWrittenIntoAnArg() {
        Pattern arg = Pattern.compile("Chat\\.arg\\(([^;]{0,200}?)\\)");
        List<String> leaking = new ArrayList<>();

        for (Map.Entry<String, String> source : sources()) {
            Matcher call = arg.matcher(source.getValue());
            while (call.find()) {
                Matcher literal = STRING_LITERAL.matcher(call.group(1));
                while (literal.find()) {
                    if (isMarkup(literal.group(1))) {
                        leaking.add(source.getKey() + ": Chat.arg(" + call.group(1).trim() + ")");
                    }
                }
            }
        }
        assertThat(leaking)
                .as("arg() escapes what it is given, so this is shown to a player as a tag rather "
                        + "than applied as a colour — use Chat.formatted with a Component, and put "
                        + "the words in messages.yml")
                .isEmpty();
    }

    @Test
    @DisplayName("no helper returns a piece of markup as a String")
    default void noHelperBuildsMarkupAsAString() {
        // The shape the real bug had: `private static String stateOf(...)` returning " <green>lifted".
        // Nothing about the call site looked wrong — Chat.arg("state", stateOf(past, now)) reads
        // perfectly — so the only place to catch it is where the markup is made.
        Pattern returning = Pattern.compile("return\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");
        List<String> building = new ArrayList<>();

        for (Map.Entry<String, String> source : sources()) {
            Matcher method = Pattern.compile("(private|static|public)[^\\n]*\\bString\\s+(\\w+)\\s*\\(")
                    .matcher(source.getValue());
            while (method.find()) {
                int from = method.end();
                int to = Math.min(source.getValue().length(), from + 700);
                Matcher returned = returning.matcher(source.getValue().substring(from, to));
                while (returned.find()) {
                    if (isMarkup(returned.group(1))) {
                        building.add(source.getKey() + "#" + method.group(2)
                                + " returns markup: \"" + returned.group(1).trim() + "\"");
                    }
                }
            }
        }
        assertThat(building)
                .as("a String of markup is a value, and every value put into a message is escaped — "
                        + "return a Component from Messages.get instead, so the words live in "
                        + "messages.yml and the colour survives")
                .isEmpty();
    }

    @Test
    @DisplayName("the wording is signed, so a line says which plugin said it")
    default void wordingIsSignedWithThisModulesBrand() {
        // Messages.defineFrom has a one-argument form that takes the wording and no signature, and a
        // section nobody has signed falls back to the global prefix — whatever plugin called
        // prefixFrom last. On a server where moderation starts after warps, "nether is set, here."
        // went out as "Moderation »", and nothing anywhere is wrong enough to notice: the sentence is
        // right, the colour is right, and the brand belongs to another plugin.
        //
        // Two of six modules passed the signature. The other four were simply written later, which is
        // the same way eighteen unmatched closing tags reached a live server — so this is checked
        // rather than remembered.
        List<String> unsigned = new ArrayList<>();
        Pattern call = Pattern.compile("defineFrom\\s*\\(([^;]*)\\)\\s*;", Pattern.DOTALL);

        for (Map.Entry<String, String> source : sources()) {
            Matcher found = call.matcher(source.getValue());
            while (found.find()) {
                if (!found.group(1).contains("chatPrefix")) {
                    unsigned.add(source.getKey());
                }
            }
        }
        assertThat(unsigned)
                .as("these call defineFrom without a signature, so every line they send wears "
                        + "whichever module plugin started last. Pass "
                        + "context.chat().brand()::chatPrefix as the second argument")
                .isEmpty();
    }

    @Test
    @DisplayName("the module hands its wording over at all")
    default void wordingIsRegistered() {
        // The other half of the same failure, and a louder one: a module that ships a messages.yml and
        // never calls defineFrom answers every key with the key itself. Claims did exactly that, and
        // /claim replied "claim.nonehere".
        assertThat(sources())
                .as("no source in %s calls Messages.defineFrom, so this module's messages.yml is "
                        + "never read and every key it defines will be printed as its own name",
                        moduleSource())
                .anyMatch(source -> source.getValue().contains("defineFrom"));
    }

    // ───────────────────────────────────────────────────────────── the reading

    /** What a client would actually draw for one line. */
    private String asShown(String miniMessage) {
        return PlainTextComponentSerializer.plainText().serialize(MINI.deserialize(filled(miniMessage)));
    }

    /** A tag whose name is a placeholder, filled with a real colour the way {@code Messages} does. */
    private String filled(String miniMessage) {
        return TAG_FROM_A_PLACEHOLDER.matcher(miniMessage).replaceAll("<$1green>");
    }

    private boolean isMarkup(String text) {
        Matcher tag = TAG.matcher(text);
        while (tag.find()) {
            if (FORMATS.contains(tag.group(2))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> wording() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(messagesFile()));
        } catch (Exception unreadable) {
            throw new AssertionError(messagesFile() + " does not parse", unreadable);
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

    /** Every line, with a list-valued key contributing one entry per line. */
    private List<Map.Entry<String, String>> wordingLines() {
        List<Map.Entry<String, String>> found = new ArrayList<>();
        wording().forEach((key, value) -> {
            if (value instanceof List<?> many) {
                for (int index = 0; index < many.size(); index++) {
                    found.add(Map.entry(key + "[" + index + "]", String.valueOf(many.get(index))));
                }
            } else {
                found.add(Map.entry(key, String.valueOf(value)));
            }
        });
        return found;
    }

    /** Every source file, with its comments stripped — these rules are about what the code does. */
    private List<Map.Entry<String, String>> sources() {
        try (Stream<Path> files = Files.walk(moduleSource())) {
            List<Map.Entry<String, String>> found = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String body = Files.readString(file)
                        .replaceAll("(?s)/\\*.*?\\*/", " ")
                        .replaceAll("(?m)//.*$", " ");
                found.add(Map.entry(file.getFileName().toString(), body));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + moduleSource(), unreadable);
        }
    }
}
