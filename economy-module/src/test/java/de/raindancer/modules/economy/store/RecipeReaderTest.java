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

    @Test
    @DisplayName("stripping is a recipe: every stripped block comes from its plain one, nothing else is invented")
    void stripping() {
        var shapes = RecipeReader.inWorld(List.of("OAK_LOG", "STRIPPED_OAK_LOG", "CRIMSON_STEM",
                "STRIPPED_CRIMSON_STEM", "STRIPPED_NOTHING", "DIRT"));
        assertThat(shapes).extracting(de.raindancer.modules.economy.model.RecipeShape::result)
                .containsExactly("STRIPPED_OAK_LOG", "STRIPPED_CRIMSON_STEM");
        assertThat(shapes.getFirst().slots()).containsExactly(List.of("OAK_LOG"));
        assertThat(shapes.getFirst().process()).isEqualTo(de.raindancer.modules.economy.model.RecipeShape.Process.WORLD);
    }

    @Test
    @DisplayName("what tools, water and time make: paths, farmland, mud, concrete, aged copper")
    void madeInTheWorld() {
        var shapes = RecipeReader.inWorld(List.of("DIRT", "GRASS_BLOCK", "DIRT_PATH", "FARMLAND", "MUD",
                "RED_CONCRETE_POWDER", "RED_CONCRETE", "COPPER_BLOCK", "EXPOSED_COPPER", "WEATHERED_COPPER",
                "OXIDIZED_COPPER", "CUT_COPPER", "EXPOSED_CUT_COPPER"));
        java.util.Map<String, List<List<String>>> by = new java.util.HashMap<>();
        shapes.forEach(shape -> by.put(shape.result(), shape.slots()));
        assertThat(by.get("DIRT_PATH")).containsExactly(List.of("DIRT", "GRASS_BLOCK"));
        assertThat(by.get("FARMLAND")).containsExactly(List.of("DIRT", "GRASS_BLOCK"));
        assertThat(by.get("MUD")).containsExactly(List.of("DIRT"));
        assertThat(by.get("RED_CONCRETE")).containsExactly(List.of("RED_CONCRETE_POWDER"));
        assertThat(by.get("EXPOSED_COPPER")).as("plain copper is a block").containsExactly(List.of("COPPER_BLOCK"));
        assertThat(by.get("WEATHERED_COPPER")).containsExactly(List.of("EXPOSED_COPPER"));
        assertThat(by.get("OXIDIZED_COPPER")).containsExactly(List.of("WEATHERED_COPPER"));
        assertThat(by.get("EXPOSED_CUT_COPPER")).containsExactly(List.of("CUT_COPPER"));
        assertThat(by).doesNotContainKey("DIRT");
    }
}
