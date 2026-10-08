package de.raindancer.modules.economy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** The raffle code picks a giveaway's line by name at runtime, so the scan of sent keys cannot see them. */
@DisplayName("every raffle line has a giveaway twin")
class GiveawayWordingTest {

    @Test
    @DisplayName("a giveaway never falls back to a missing line")
    void twins() throws Exception {
        try (var in = new InputStreamReader(Objects.requireNonNull(
                EconomyModule.class.getResourceAsStream("messages.yml")), StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(in);
            var raffle = yaml.getConfigurationSection("economy.raffle");
            assertThat(raffle).isNotNull();
            assertThat(raffle.getKeys(false)).allSatisfy(key ->
                    assertThat(yaml.isString("economy.giveaway." + key)).as("economy.giveaway." + key).isTrue());
        }
    }
}
