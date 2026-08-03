package de.raindancer.modules.claims;

import de.raindancer.modules.claims.util.Placeholders;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a bag of placeholders reaches {@code Messages} in the shape it expects.
 *
 * <h2>The defect this exists because of</h2>
 * Core's {@code Messages} takes its values as {@code Object...} — alternating name, value, name, value.
 * Three services here built a {@code Map<String, String>} instead and handed the map over as a single
 * argument. Java accepts that silently, because a {@code Map} is an {@code Object}: the array has one
 * element, no name is ever read, and <b>nothing is substituted</b>.
 *
 * <p>So a player was told:
 *
 * <pre>
 *   &lt;player&gt; was barred from &lt;claim&gt; by &lt;by&gt;. &lt;reason&gt;
 *   You were shown out of &lt;claim&gt;.
 * </pre>
 *
 * <p>It compiles, no test failed, nothing was logged, and it is only visible by reading the message a
 * real player receives. Which is how it was found — in a screenshot of somebody's chat.
 */
class PlaceholdersTest {

    @Test
    @DisplayName("a map becomes the name, value, name, value the message API wants")
    void flattened() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", "Berry_The_Jerry");
        values.put("claim", "poopy_claim");

        assertThat(Placeholders.of(values))
                .containsExactly("player", "Berry_The_Jerry", "claim", "poopy_claim");
    }

    @Test
    @DisplayName("nothing in, empty out — never a one-element array holding the map")
    void empties() {
        // The failure mode itself: an array of length one, whose single element is the map. Anything
        // that produced that would render the placeholders raw again.
        assertThat(Placeholders.of(Map.of())).isEmpty();
        assertThat(Placeholders.of(null)).isEmpty();
    }

    @Test
    @DisplayName("every pair survives, so the count is always even")
    void alwaysPairs() {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            values.put("key" + i, "value" + i);
        }

        Object[] flat = Placeholders.of(values);

        assertThat(flat).hasSize(14);
        assertThat(flat.length % 2).as("an odd count means a name with no value").isZero();
    }

    @Test
    @DisplayName("a null value is carried as an empty string rather than the word null")
    void nullValues() {
        // MiniMessage renders a null argument as the text "null", which reads as a bug to the player
        // and hides which value was actually missing.
        Map<String, String> values = new LinkedHashMap<>();
        values.put("reason", null);

        assertThat(Placeholders.of(values)).containsExactly("reason", "");
    }

    @Test
    @DisplayName("no service hands a whole map to a message call")
    void nobodyPassesAMapDirectly() {
        // The guard. A Map is an Object, so this mistake type-checks — the compiler will never catch
        // it and neither will any test of the services, which need a running server to exercise.
        Pattern declaration = Pattern.compile("Map<String,\\s*String>\\s+(\\w+)");
        Pattern messageCall = Pattern.compile(
                "messages(\\(\\))?\\.(send|sendPlain|get|prefixed|variant)\\([^;]*?,\\s*(\\w+)\\s*\\)");

        List<String> wrong = new ArrayList<>();
        for (Path file : sources()) {
            String body = read(file);

            List<String> maps = new ArrayList<>();
            Matcher declared = declaration.matcher(body);
            while (declared.find()) {
                maps.add(declared.group(1));
            }
            if (maps.isEmpty()) {
                continue;
            }
            Matcher call = messageCall.matcher(body);
            while (call.find()) {
                if (maps.contains(call.group(3))) {
                    wrong.add(file.getFileName() + ": " + call.group().replaceAll("\\s+", " "));
                }
            }
        }

        assertThat(wrong)
                .as("Messages takes name, value, name, value — a map handed over whole is one silent "
                        + "argument and nothing is substituted. Use Placeholders.of(map)")
                .isEmpty();
    }

    private static List<Path> sources() {
        Path root = Path.of("src/main/java/de/raindancer/modules/claims");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module", unreadable);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }
}
