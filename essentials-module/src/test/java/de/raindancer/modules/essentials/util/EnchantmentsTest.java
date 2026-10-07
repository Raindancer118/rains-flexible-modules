package de.raindancer.modules.essentials.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnchantmentsTest {

    @Test
    @DisplayName("a typed key loses its minecraft: prefix, its case and its spaces")
    void normalises() {
        assertThat(Enchantments.normalise("minecraft:Sharpness")).isEqualTo("sharpness");
        assertThat(Enchantments.normalise("  Fire Aspect ")).isEqualTo("fire_aspect");
        assertThat(Enchantments.normalise("MINECRAFT:fire_aspect")).isEqualTo("fire_aspect");
    }

    @Test
    @DisplayName("another namespace is kept, because another plugin's enchantment lives there")
    void keepsOtherNamespaces() {
        assertThat(Enchantments.normalise("MyPlugin:Vampirism")).isEqualTo("myplugin:vampirism");
    }

    @Test
    @DisplayName("a key reads as words")
    void readable() {
        assertThat(Enchantments.readable("fire_aspect")).isEqualTo("Fire Aspect");
        assertThat(Enchantments.readable("minecraft:sweeping_edge")).isEqualTo("Sweeping Edge");
        assertThat(Enchantments.readable("myplugin:vampirism")).isEqualTo("Vampirism");
    }

    @Test
    @DisplayName("the enchantment line is the name and the plain number, which a client could not draw above ten")
    void line() {
        assertThat(Enchantments.line("sharpness", 255)).isEqualTo("Sharpness 255");
        assertThat(Enchantments.line("minecraft:mending", 1)).isEqualTo("Mending 1");
    }
}
