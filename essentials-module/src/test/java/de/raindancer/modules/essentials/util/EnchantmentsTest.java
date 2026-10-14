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

    @Test
    @DisplayName("a plugin's enchantment is found by its name alone, unless vanilla or two plugins share that name")
    void bareName() {
        java.util.List<org.bukkit.NamespacedKey> known = java.util.List.of(org.bukkit.NamespacedKey.minecraft("sharpness"),
                new org.bukkit.NamespacedKey("rainsenchants", "telekinesis"), new org.bukkit.NamespacedKey("rainsenchants", "boost"),
                new org.bukkit.NamespacedKey("other", "boost"), new org.bukkit.NamespacedKey("rainsenchants", "sharpness"));

        assertThat(Enchantments.resolve("telekinesis", known)).contains(new org.bukkit.NamespacedKey("rainsenchants", "telekinesis"));
        assertThat(Enchantments.resolve("sharpness", known)).as("vanilla first").contains(org.bukkit.NamespacedKey.minecraft("sharpness"));
        assertThat(Enchantments.resolve("boost", known)).as("two plugins: say which").isEmpty();
        assertThat(Enchantments.resolve("other:boost", known)).contains(new org.bukkit.NamespacedKey("other", "boost"));
        assertThat(Enchantments.resolve("nothing", known)).isEmpty();
    }
}
