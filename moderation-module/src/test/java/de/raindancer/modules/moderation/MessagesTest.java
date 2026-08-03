package de.raindancer.modules.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the wording renders as wording.
 *
 * <h2>The defect this is about</h2>
 * MiniMessage prints an unmatched closing tag <em>literally</em>. So
 * {@code "<green><player></white> is now"} — which opens green and closes a white nobody opened — reaches
 * a player as <b>"Raindancer118&lt;/white&gt; is now Helper."</b> It compiles, it passes every other test,
 * and it is only visible by reading a real message on a real server. Which is exactly how it was found:
 * in the console output of the first live promotion.
 *
 * <p>Cheap to check and worth checking, because every line in this file is one somebody reads.
 */
class MessagesTest {

    private static final Path MESSAGES = Path.of("src/main/resources/messages.yml");

    /** A MiniMessage tag: {@code <white>}, {@code </white>}, {@code <player>}, {@code <colour>}. */
    private static final Pattern TAG = Pattern.compile("</?([a-z_][a-z0-9_:#-]*)>");

    /**
     * Tags that are colours and formats — the ones that can be closed.
     *
     * <p>Anything else in angle brackets is a <em>placeholder</em> the code fills in, and those are
     * never closed. Listing the closable ones rather than the placeholders because the placeholders are
     * different per line and the colours are the same everywhere.
     */
    private static final List<String> CLOSABLE = List.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
            "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "italic", "underlined", "strikethrough", "obfuscated");

    private static List<String> lines() {
        try {
            return Files.readAllLines(MESSAGES);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read messages.yml", unreadable);
        }
    }

    @Test
    @DisplayName("the file is read, so this cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(lines()).hasSizeGreaterThan(50);
        assertThat(String.join("\n", lines())).contains("moderation:");
    }

    @Test
    @DisplayName("no line closes a colour it never opened")
    void noUnmatchedClosingTags() {
        List<String> broken = new ArrayList<>();

        for (String line : lines()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.contains("<")) {
                continue;
            }
            Deque<String> open = new ArrayDeque<>();
            Matcher tags = TAG.matcher(trimmed);
            while (tags.find()) {
                String name = tags.group(1);
                if (!CLOSABLE.contains(name)) {
                    continue;   // a placeholder, which is never opened or closed
                }
                if (tags.group().startsWith("</")) {
                    if (!open.remove(name)) {
                        broken.add(trimmed + "   ← closes <" + name + "> without opening it");
                        break;
                    }
                } else {
                    open.push(name);
                }
            }
        }

        assertThat(broken)
                .as("MiniMessage prints an unmatched closing tag literally, so this reaches a player "
                        + "as the text \"</white>\" in the middle of a sentence")
                .isEmpty();
    }

    @Test
    @DisplayName("every placeholder is written the way the code fills it in")
    void placeholdersAreLowerCase() {
        // Messages.send matches placeholder names literally, so <Player> is not <player> — it renders
        // as the text "<Player>" and the name never appears.
        List<String> odd = new ArrayList<>();
        for (String line : lines()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            Matcher tags = TAG.matcher(trimmed);
            while (tags.find()) {
                String name = tags.group(1);
                if (!name.equals(name.toLowerCase(java.util.Locale.ROOT))) {
                    odd.add(trimmed);
                }
            }
        }
        assertThat(odd).isEmpty();
    }

    @Test
    @DisplayName("the file parses as YAML at all")
    void itIsValidYaml() {
        // A tab, or a colon inside an unquoted value, and the whole file silently supplies nothing —
        // every line then falls back to its key, which reads as a broken plugin rather than a broken
        // file.
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            yaml.loadFromString(String.join("\n", lines()));
        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
            throw new AssertionError("messages.yml does not parse: " + broken.getMessage(), broken);
        }
        assertThat(yaml.getKeys(true)).isNotEmpty();
    }
}
