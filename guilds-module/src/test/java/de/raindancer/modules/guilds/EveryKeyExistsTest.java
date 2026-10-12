package de.raindancer.modules.guilds;

import de.raindancer.modules.guilds.model.GuildOutcome;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every key the code names is in messages.yml as the server reads it — and every outcome has its sentence. */
class EveryKeyExistsTest {

    /** Economy source keys and the outcome prefix: written like message keys, are not messages. */
    private static final Set<String> NOT_MESSAGES = Set.of("guilds.bank", "guilds.found", "guilds.outcome.");

    private static YamlConfiguration wording() {
        return YamlConfiguration.loadConfiguration(Path.of("src/main/resources/de/raindancer/modules/guilds/messages.yml").toFile());
    }

    @Test
    @DisplayName("every guilds.* key in the source is defined, as YAML actually parses it")
    void defined() throws IOException {
        YamlConfiguration yaml = wording();
        Pattern key = Pattern.compile("\"(guilds\\.[a-z0-9.-]+)\"");
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
                    .filter(found -> !NOT_MESSAGES.contains(found))
                    .distinct().toList();
        }
        assertThat(used).as("the scan found no keys, so it checks nothing").hasSizeGreaterThan(40);
        assertThat(used).allSatisfy(name -> assertThat(yaml.isString(name)).as(name).isTrue());
    }

    @Test
    @DisplayName("every outcome a command or a screen can say has a sentence")
    void everyOutcome() {
        YamlConfiguration yaml = wording();
        assertThat(Arrays.stream(GuildOutcome.values()).map(GuildOutcome::messageKey))
                .allSatisfy(name -> assertThat(yaml.isString(name)).as(name).isTrue());
    }
}
