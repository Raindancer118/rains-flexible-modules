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
 * That every message this module sends is a message it has.
 *
 * <p>A key with no wording behind it does not fail, log, or look like a bug from the inside — the player
 * gets the key. {@code /claim} answered {@code <claim.none-here>} on a live server for exactly this reason,
 * and nothing in 156 passing tests had an opinion about it, because no test had ever compared the keys the
 * code uses against the file that answers them.
 *
 * <p>Held as a source scan because the two things being compared are a set of string literals and a YAML
 * file, and both are exactly readable without a server.
 */
class EveryMessageExistsTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/modules/claims");
    private static final Path MESSAGES = Path.of("src/main/resources/de/raindancer/modules/claims/messages.yml");

    /**
     * The first argument of a message call.
     *
     * <p>Deliberately tied to the call rather than matching every dotted string in the file: a Material name,
     * a permission node and a settings path all look like message keys otherwise.
     */
    private static final Pattern USED = Pattern.compile(
            "(?:messages\\(\\)|messages)\\s*\\.\\s*(?:send|sendPlain|get|prefixed|raw|variant|lines|has)"
                    // The recipient, when there is one, is a plain name — viewer, player, sender. Allowing
                    // a call there instead made the pattern swallow `raw(flag.nameKey()), "value"` and report
                    // the placeholder NAME as a missing key.
                    + "\\s*\\(\\s*(?:[A-Za-z_][A-Za-z0-9_]*\\s*,\\s*)?\"([a-z][a-z0-9.-]*)\"");

    private static Set<String> keysUsedInCode() {
        Set<String> used = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = USED.matcher(Files.readString(file));
                while (matcher.find()) {
                    used.add(matcher.group(1));
                }
            }
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module's sources", unreadable);
        }
        return used;
    }

    private static Map<String, Object> keysInTheFile() {
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

    @Test
    @DisplayName("the scan actually found keys, so a refactor cannot quietly empty it")
    void theScanIsNotVacuous() {
        assertThat(keysUsedInCode())
                .as("if this is empty the pattern stopped matching and this whole test passes on nothing")
                .hasSizeGreaterThan(30);
        assertThat(keysInTheFile()).hasSizeGreaterThan(30);
    }

    @Test
    @DisplayName("every key the code sends is in messages.yml")
    void nothingIsSentThatIsNotWritten() {
        Map<String, Object> have = keysInTheFile();
        List<String> missing = new ArrayList<>();
        for (String key : keysUsedInCode()) {
            if (!have.containsKey(key)) {
                missing.add(key);
            }
        }
        assertThat(missing)
                .as("these are sent by the code and have no wording, so the player is shown the key itself — "
                        + "which does not fail, does not log, and does not look like a bug from the inside")
                .isEmpty();
    }

    @Test
    @DisplayName("the file carries a prefix, since it is the module's own voice")
    void thePrefixIsThere() {
        assertThat(keysInTheFile()).containsKey("prefix");
    }

    @Test
    @DisplayName("no message is left blank")
    void nothingIsEmpty() {
        List<String> blank = new ArrayList<>();
        keysInTheFile().forEach((key, value) -> {
            if (value instanceof String text && text.isBlank()) {
                blank.add(key);
            }
        });
        assertThat(blank)
                .as("a blank message is indistinguishable from the plugin ignoring the player; if silence "
                        + "is wanted, the code should not send at all")
                .isEmpty();
    }
}
