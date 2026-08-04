package de.raindancer.modules.names;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That every line renders as a sentence rather than as its own markup.
 *
 * <h2>The bug this is about</h2>
 * A player walking into a claim was told:
 *
 * <pre>You are entering Home, owned by Raindancer118&lt;/gray&gt;.</pre>
 *
 * The wording was {@code <gray>You are entering <white><claim></gray>, owned by <white><owner></gray>.}
 * — it opened {@code white} twice and closed {@code gray} twice. MiniMessage does not treat that as an
 * error: a closing tag with no matching open tag left is kept exactly as written and printed to the
 * player. So nothing failed, nothing was logged, and the plugin looked broken to everybody except
 * whoever reads {@code messages.yml}.
 *
 * <p>And it only misbehaved for half the line, which is why it survived being read: the first
 * {@code </gray>} closed the {@code <gray>} still open from the start of the sentence and vanished
 * quietly. By the second one nothing was open, so that one was printed.
 *
 * <p>Eight lines of the claims module had it, and this module had none — which is exactly why the test
 * is repeated here rather than left where the bug was. The wording that is clean today is clean until
 * somebody edits it, and the failure leaves no trace anywhere except in front of a player.
 *
 * <h2>Why it asks the real parser</h2>
 * Because the rule is not "the tags balance" — plenty of these lines deliberately never close anything,
 * and MiniMessage is perfectly happy with that. The rule is <b>no player is ever shown a tag</b>, and
 * the only thing that knows what a player is shown is MiniMessage itself. So each line is rendered and
 * flattened to the text a client would draw, and that text must not contain markup.
 *
 * <p>Which also means this test cannot drift from the parser: an Adventure release that changed how an
 * unmatched tag is handled would change this test's answer along with the game's.
 */
class WordingRendersTest {

    private static final Path MESSAGES =
            Path.of("src/main/resources/de/raindancer/modules/names/messages.yml");

    private static final MiniMessage MINI = MiniMessage.miniMessage();

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

    /** Every line, with a list-valued key contributing one entry per line. */
    private static List<Map.Entry<String, String>> lines() {
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

    /**
     * A placeholder standing where a tag name goes: {@code <<colour>>…</<colour>>}.
     *
     * <p>A real pattern, not a mistake — the colour is chosen at the moment the line is sent, so a rank
     * renders in its own colour. It has to be filled in before the line can be judged, because
     * unfilled it is two unknown tags and the closing one reads exactly like the bug this hunts.
     */
    private static final java.util.regex.Pattern TAG_FROM_A_PLACEHOLDER =
            java.util.regex.Pattern.compile("<(/?)<([a-z][a-z0-9-]*)>>");

    /**
     * What a client would actually draw.
     *
     * <p>Placeholders standing in for a tag name are filled with a real colour first, the way
     * {@code Messages} fills them at send time. Every other placeholder is left alone: unfilled it
     * renders as {@code <claim>}, which is not markup and is not what this test is about.
     */
    private static String asShown(String miniMessage) {
        String filled = TAG_FROM_A_PLACEHOLDER.matcher(miniMessage).replaceAll("<$1green>");
        return PlainTextComponentSerializer.plainText().serialize(MINI.deserialize(filled));
    }

    @Test
    @DisplayName("the scan reads the wording, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(lines()).hasSizeGreaterThan(10);
        // And it can tell the two apart. This is the line verbatim, and it is worth keeping as the
        // example because it also shows *why* the bug survived a reading: MiniMessage consumes an
        // unmatched close while a matching tag is still open somewhere, so the first </gray> here
        // vanishes quietly and only the second one — by which point nothing is open — reaches the
        // player. Half a line behaving correctly is what made this look like a typo in one place.
        assertThat(asShown("<gray>You are entering <white><claim></gray>, owned by "
                + "<white><owner></gray>."))
                .contains("</gray>");
        assertThat(asShown("<gray>You are entering <white><claim></white>, owned by "
                + "<white><owner></white>."))
                .doesNotContain("</");
    }

    @Test
    @DisplayName("no message shows a closing tag to the player")
    void nothingLeaksItsOwnMarkup() {
        List<String> leaking = new ArrayList<>();
        for (Map.Entry<String, String> line : lines()) {
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
    void everythingParses() {
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> line : lines()) {
            try {
                MINI.deserialize(line.getValue());
            } catch (RuntimeException unparseable) {
                broken.add(line.getKey() + ": " + unparseable.getMessage());
            }
        }
        assertThat(broken).isEmpty();
    }
}
