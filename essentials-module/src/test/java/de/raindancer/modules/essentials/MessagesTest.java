package de.raindancer.modules.essentials;

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
 * That the wording renders as wording, and that every key the code sends is defined — the same checks
 * moderation-module's own {@code MessagesTest} makes, against the same file shape.
 */
class MessagesTest {

    private static final Path MESSAGES =
            Path.of("src/main/resources/de/raindancer/modules/essentials/messages.yml");
    private static final Path SOURCES = Path.of("src/main/java/de/raindancer/modules/essentials");

    private static final Pattern TAG = Pattern.compile("</?([a-z_][a-z0-9_:#-]*)>");

    private static final List<String> CLOSABLE = List.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold",
            "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "bold", "italic", "underlined", "strikethrough", "obfuscated");

    private static final List<String> YAML_BOOLEANS = List.of(
            "on", "off", "yes", "no", "true", "false", "y", "n");

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
        assertThat(lines()).hasSizeGreaterThan(20);
        assertThat(String.join("\n", lines())).contains("essentials:");
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
                    continue;
                }
                if (tags.group().startsWith("</")) {
                    if (!open.remove(name)) {
                        broken.add(trimmed + "   <- closes <" + name + "> without opening it");
                        break;
                    }
                } else {
                    open.push(name);
                }
            }
        }
        assertThat(broken).isEmpty();
    }

    @Test
    @DisplayName("every placeholder is written the way the code fills it in")
    void placeholdersAreLowerCase() {
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
    @DisplayName("no message key is a word YAML turns into a boolean")
    void noBooleanKeys() {
        List<String> booleanKeys = new ArrayList<>();
        for (String line : lines()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.contains(":")) {
                continue;
            }
            String key = trimmed.substring(0, trimmed.indexOf(':')).trim();
            if (YAML_BOOLEANS.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                booleanKeys.add(line.strip());
            }
        }
        assertThat(booleanKeys).isEmpty();
    }

    @Test
    @DisplayName("the file parses as YAML, and every key the code writes out as a literal is defined")
    void everyLiteralKeyExists() {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            yaml.loadFromString(String.join("\n", lines()));
        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
            throw new AssertionError("messages.yml does not parse", broken);
        }
        assertThat(yaml.getKeys(true)).isNotEmpty();

        Pattern literal = Pattern.compile("\"(essentials\\.[a-z0-9.\\-]+)\"");
        List<String> undefined = new ArrayList<>();
        for (Path file : sources()) {
            if (file.getFileName().toString().equals("PermissionNodes.java")) {
                continue;   // permission nodes, not message keys — same "essentials." prefix
            }
            String body = read(file);
            Matcher keys = literal.matcher(body);
            while (keys.find()) {
                String key = keys.group(1);
                if (key.endsWith(".") || key.endsWith("-") || key.endsWith(".yml")
                        || yaml.isSet(key)) {
                    continue;
                }
                undefined.add(key + "   (" + file.getFileName() + ")");
            }
        }
        assertThat(undefined)
                .as("these are sent by the code and defined nowhere, so a player is shown the key")
                .isEmpty();
    }

    private static List<Path> sources() {
        try (var files = Files.walk(SOURCES)) {
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
