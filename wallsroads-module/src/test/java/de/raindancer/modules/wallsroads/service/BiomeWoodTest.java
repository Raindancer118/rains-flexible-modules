package de.raindancer.modules.wallsroads.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which wood a place builds with — the wood growing beside it. */
class BiomeWoodTest {

    @Test
    @DisplayName("a swamp builds in mangrove, a taiga in spruce, a jungle in jungle wood")
    void followsTheTreesThatGrowThere() {
        assertThat(BiomeWood.logFor("mangrove_swamp")).isEqualTo("MANGROVE_LOG");
        assertThat(BiomeWood.logFor("taiga")).isEqualTo("SPRUCE_LOG");
        assertThat(BiomeWood.logFor("old_growth_pine_taiga")).isEqualTo("SPRUCE_LOG");
        assertThat(BiomeWood.logFor("jungle")).isEqualTo("JUNGLE_LOG");
        assertThat(BiomeWood.logFor("savanna")).isEqualTo("ACACIA_LOG");
        assertThat(BiomeWood.logFor("desert")).isEqualTo("ACACIA_LOG");
        assertThat(BiomeWood.logFor("cherry_grove")).isEqualTo("CHERRY_LOG");
        assertThat(BiomeWood.logFor("dark_forest")).isEqualTo("DARK_OAK_LOG");
        assertThat(BiomeWood.logFor("birch_forest")).isEqualTo("BIRCH_LOG");
    }

    @Test
    @DisplayName("a namespaced biome key is understood too — that is what a server actually hands over")
    void acceptsANamespacedKey() {
        assertThat(BiomeWood.logFor("minecraft:taiga")).isEqualTo("SPRUCE_LOG");
    }

    @Test
    @DisplayName("somewhere unknown, or nowhere at all, builds in oak")
    void fallsBackToOak() {
        assertThat(BiomeWood.logFor("somewhere_a_datapack_invented")).isEqualTo("OAK_LOG");
        assertThat(BiomeWood.logFor(null)).isEqualTo("OAK_LOG");
        assertThat(BiomeWood.logFor("")).isEqualTo("OAK_LOG");
    }

    @Test
    @DisplayName("the planks and the stripped log match the log, so a trestle is all one wood")
    void keepsAFamilyTogether() {
        assertThat(BiomeWood.planksFor("taiga")).isEqualTo("SPRUCE_PLANKS");
        assertThat(BiomeWood.strippedFor("taiga")).isEqualTo("STRIPPED_SPRUCE_LOG");
        assertThat(BiomeWood.fenceFor("mangrove_swamp")).isEqualTo("MANGROVE_FENCE");
    }
}
