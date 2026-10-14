package de.raindancer.e2e.modules;

import de.raindancer.e2e.Bot;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enchantments as a client receives them. Geyser builds the enchantment registry for Bedrock players from this
 * packet, and an {@code exclusive_set} naming enchantments one by one makes it look them up before the registry is
 * done — it fails, and every item a Bedrock player holds is left untranslated: an empty inventory (Lilly, 14.10.2026).
 * Vanilla only ever sends a tag there.
 */
@Tag("e2e")
class EnchantsRegistryScenarioTest {

    @Test
    @DisplayName("every enchantment's exclusive set is a tag (or none), so Geyser can read the registry")
    void exclusiveSetsAreTags() {
        try (Server server = Server.start("enchants-registry", List.of("enchants-standalone:RainsEnchants-.*"),
                List.of("Enchants are up"))) {
            Bot player = server.player("Bedrockish");
            var enchantments = player.registry("minecraft:enchantment");
            assertThat(enchantments).as("the server sent the enchantment registry")
                    .anyMatch(entry -> entry.getId().namespace().equals("rainsenchants"));

            List<String> inline = new ArrayList<>();
            for (var entry : enchantments) {
                if (!(entry.getData() instanceof NbtMap data) || !data.containsKey("exclusive_set")) {
                    continue;
                }
                Object set = data.get("exclusive_set");
                if (!(set instanceof String tag && tag.startsWith("#"))) {
                    inline.add(entry.getId().asString() + " = " + set);
                }
            }
            assertThat(inline).as("exclusive sets naming enchantments directly").isEmpty();
        }
    }
}
