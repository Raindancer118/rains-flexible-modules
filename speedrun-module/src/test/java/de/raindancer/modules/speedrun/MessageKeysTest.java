package de.raindancer.modules.speedrun;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message the code names exists — read the way the server reads it, through Bukkit's own YAML.
 *
 * <p>Found by the end-to-end run: {@code /speedrun spectate} answered with the bare key
 * {@code <speedrun.spectate.on>}, because YAML 1.1 reads an unquoted {@code on:} as the boolean
 * {@code true}, so the key the code asked for was never there. Every wording test passed — they read
 * the file with a parser that kept {@code on} a string.
 */
class MessageKeysTest {

    private static final Pattern KEY = Pattern.compile("\"((?:speedrun|manhunt)\\.[a-z0-9-]+(?:\\.[a-z0-9-]+)+)\"(?!\\s*\\+)");

    @Test
    @DisplayName("every message key the code names is in messages.yml, as the server reads it")
    void everyKeyResolves() throws IOException {
        YamlConfiguration messages;
        try (InputStreamReader reader = new InputStreamReader(
                SpeedrunSettings.class.getResourceAsStream("messages.yml"), StandardCharsets.UTF_8)) {
            messages = YamlConfiguration.loadConfiguration(reader);
        }
        TreeSet<String> missing = new TreeSet<>();
        List<String> named = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher key = KEY.matcher(Files.readString(file));
                while (key.find()) {
                    named.add(key.group(1));
                    if (!messages.isString(key.group(1))) {
                        missing.add(key.group(1) + " (" + file.getFileName() + ")");
                    }
                }
            }
        }

        assertThat(named).as("the scan finds the code's keys").hasSizeGreaterThan(100);
        assertThat(missing).as("keys the code names that the server would not find").isEmpty();
    }
}
