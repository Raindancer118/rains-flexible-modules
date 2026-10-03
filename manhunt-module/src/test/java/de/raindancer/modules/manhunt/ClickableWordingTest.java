package de.raindancer.modules.manhunt;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every line that tells somebody to type a command is also the button that types it — the brief was
 * "every chat output that suggests an action is a clickable button".
 */
@DisplayName("every suggested command is a button")
class ClickableWordingTest {

    @Test
    void noBareCommands() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(Path.of("src/main/resources/de/raindancer/modules/manhunt/messages.yml")));
        List<String> bare = new ArrayList<>();
        walk(yaml, "", bare);
        assertThat(bare).as("lines naming a command with no button to click").isEmpty();
    }

    private static void walk(ConfigurationSection section, String prefix, List<String> bare) {
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            String path = prefix + key;
            if (value instanceof ConfigurationSection nested) {
                walk(nested, path + ".", bare);
            } else if (value instanceof String line) {
                String visible = line.replaceAll("<click:[^>]*>.*?</click>", "");
                boolean namesOne = visible.matches(".*(^|[\\s>])/(manhunt|whitelist|settings)\\b.*");
                if (namesOne && !line.contains("<click:")) {
                    bare.add(path + ": " + line);
                }
            }
        }
    }
}
