package de.raindancer.modules.hungergames;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every line written for an item is a line an item actually says.
 *
 * <h2>The regression, and why it needed a test rather than a fix</h2>
 * The source plugin answered every single item use with a sentence — how many the smoke bomb caught, how
 * many seconds of flight the boots gave, that the grappling hook fired at all — and the port answered none
 * of them. The effects happened, the cues played, and nobody was told anything. Half of those sentences
 * carry a number the player has no other way to see, and an item that gives no feedback at all is one people
 * click twice because the first click looked like nothing happening.
 *
 * <p>{@code EveryMessageExistsTest} checks the other direction: that every key the code sends has wording.
 * That one cannot catch this, because the failure here is wording nothing sends. Both directions together
 * are what make the wording file describe the plugin rather than an earlier draft of it.
 */
class EveryItemAnswersItsHolderTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/modules/hungergames");
    private static final Path WORDING =
            Path.of("src/main/resources/de/raindancer/modules/hungergames/messages.yml");

    /** Every {@code item-…} key in the module's own wording file. */
    private static List<String> itemKeys() {
        List<String> keys = new ArrayList<>();
        for (String line : read(WORDING).lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith("item-") && trimmed.contains(":")) {
                keys.add(trimmed.substring(0, trimmed.indexOf(':')));
            }
        }
        return keys;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    private static String allSource() {
        try (Stream<Path> files = Files.walk(SOURCE)) {
            StringBuilder everything = new StringBuilder();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                everything.append(read(file));
            }
            return everything.toString();
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module", unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the item wording, so it cannot pass by finding none")
    void theScanIsNotVacuous() {
        assertThat(itemKeys())
                .as("fourteen items, most of which say more than one thing")
                .hasSizeGreaterThan(15);
    }

    @Test
    @DisplayName("every line written for an item is one something actually sends")
    void nothingIsWrittenAndNeverSaid() {
        String source = allSource();

        List<String> unsaid = itemKeys().stream()
                .filter(key -> !source.contains("hungergames." + key))
                .toList();

        assertThat(unsaid)
                .as("wording nothing sends is an item that stays silent, and the file makes it look as "
                        + "though it does not")
                .isEmpty();
    }
}
