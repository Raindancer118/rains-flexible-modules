package de.raindancer.modules.ctf;

import de.raindancer.core.world.combat.Streaks;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every key the code names is in messages.yml, read the way the server reads it — the made-up ones too. */
class EveryKeyExistsTest {

    @Test
    @DisplayName("every ctf.* key in the source is defined, as YAML actually parses it")
    void defined() throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/de/raindancer/modules/ctf/messages.yml").toFile());
        Pattern key = Pattern.compile("\"(ctf\\.[a-z0-9-]+(?:\\.[a-z0-9-]+)+)\"");
        List<String> used = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path path : sources.filter(file -> file.toString().endsWith(".java")).toList()) {
                key.matcher(Files.readString(path)).results().map(found -> found.group(1)).forEach(used::add);
            }
        }
        for (Streaks.Callout callout : Streaks.Callout.values()) {
            used.add("ctf.announce." + callout.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        }
        assertThat(used).as("the scan found no keys, so it checks nothing").hasSizeGreaterThan(100);
        assertThat(used.stream().distinct()).allSatisfy(name -> assertThat(yaml.isString(name)).as(name).isTrue());
    }
}
