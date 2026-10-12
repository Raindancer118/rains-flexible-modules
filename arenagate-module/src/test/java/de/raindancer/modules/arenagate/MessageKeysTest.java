package de.raindancer.modules.arenagate;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every line the code asks for is a line the file has — a missing key reads as its own name to a player. */
class MessageKeysTest {

    private static final Path SOURCE = Path.of("src/main/java/de/raindancer/modules/arenagate");
    private static final Path MESSAGES = Path.of("src/main/resources/de/raindancer/modules/arenagate/messages.yml");
    private static final Pattern KEY = Pattern.compile("\"(arenagate\\.[a-z][a-z.-]*)\"");

    @Test
    @DisplayName("every message key used in the code is in messages.yml")
    void everyKeyExists() throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(MESSAGES.toFile());
        List<String> used = new ArrayList<>(new TreeSet<>(keysUsed()));
        assertThat(used).hasSizeGreaterThan(5);
        List<String> missing = used.stream().filter(key -> !yaml.isString(key)).toList();
        assertThat(missing).as("used in the code, absent from messages.yml").isEmpty();
    }

    private static List<String> keysUsed() throws IOException {
        List<String> keys = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher found = KEY.matcher(Files.readString(file));
                while (found.find()) {
                    keys.add(found.group(1));
                }
            }
        }
        return keys;
    }
}
