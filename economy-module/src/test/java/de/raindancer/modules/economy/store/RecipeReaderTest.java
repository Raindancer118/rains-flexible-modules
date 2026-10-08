package de.raindancer.modules.economy.store;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeReaderTest {

    @Test
    @DisplayName("item types named by key read as material names — how Paper hands over smithing ingredients")
    void itemTypeKeys() {
        assertThat(RecipeReader.materialNames(List.of(Key.key("minecraft:diamond_sword"),
                Key.key("minecraft:netherite_upgrade_smithing_template"))))
                .containsExactly("DIAMOND_SWORD", "NETHERITE_UPGRADE_SMITHING_TEMPLATE");
        assertThat(RecipeReader.materialNames(List.of(Key.key("somepack:ruby")))).as("not a vanilla item").isEmpty();
    }
}
