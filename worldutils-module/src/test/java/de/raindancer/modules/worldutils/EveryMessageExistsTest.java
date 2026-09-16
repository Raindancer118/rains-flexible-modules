package de.raindancer.modules.worldutils;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every key the code sends has wording. A missing one is answered with the key itself, which reads to a
 * player as a broken plugin and fails nothing anywhere.
 */
class EveryMessageExistsTest {

    private static final Path SOURCES = Path.of("src/main/java/de/raindancer/modules/worldutils");
    private static final Path WORDING = Path.of("src/main/resources/de/raindancer/modules/worldutils/messages.yml");

    @Test
    @DisplayName("every worldutils.* key in the code is in messages.yml")
    void everyKeyHasWording() throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(WORDING.toFile());
        Pattern key = Pattern.compile("\"(worldutils\\.[a-z0-9.-]+)\"");
        List<String> used = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher found = key.matcher(Files.readString(file));
                while (found.find()) {
                    used.add(found.group(1));
                }
            }
        }
        // The two the confirmation builds from its question's key.
        for (String question : List.of("worldutils.confirm.regen", "worldutils.confirm.delete")) {
            used.add(question + "-title");
            used.add(question + "-detail");
        }

        assertThat(used).as("the scan found no keys, so it is reading the wrong place").hasSizeGreaterThan(20);
        assertThat(used.stream().filter(k -> !yaml.contains(k)
                        && !k.equals("worldutils.confirm.regen") && !k.equals("worldutils.confirm.delete")).toList())
                .as("sent from the code, but with no wording")
                .isEmpty();
    }
}
