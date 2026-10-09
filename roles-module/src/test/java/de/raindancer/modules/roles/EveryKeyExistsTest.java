package de.raindancer.modules.roles;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every key the code names is in messages.yml, read the way the server reads it. Written after a key
 * called {@code off} reached a live test as "<roles.off>": YAML reads {@code on} and {@code off}
 * as true and false, so the file looked right and the key did not exist.
 */
class EveryKeyExistsTest {

    @Test
    @DisplayName("every roles.* key in the source is defined, as YAML actually parses it")
    void defined() throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/de/raindancer/modules/roles/messages.yml").toFile());
        Pattern key = Pattern.compile("\"(roles\\.[a-z0-9.-]+)\"");
        List<String> used;
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            used = sources.filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> {
                        try {
                            return key.matcher(Files.readString(path)).results().map(found -> found.group(1));
                        } catch (IOException unreadable) {
                            throw new IllegalStateException(unreadable);
                        }
                    })
                    .filter(found -> !found.endsWith(".yml")) // roles.yml is a file, not a message
                    .filter(found -> !found.equals("roles.buy") && !found.equals("roles.rent")) // economy sources
                    .distinct().toList();
        }

        assertThat(used).as("the scan found no keys, so it checks nothing").hasSizeGreaterThan(5);
        assertThat(used).allSatisfy(name -> assertThat(yaml.isString(name)).as(name).isTrue());
    }
}
