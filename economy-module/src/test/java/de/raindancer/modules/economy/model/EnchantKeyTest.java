package de.raindancer.modules.economy.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnchantKeyTest {

    @Test
    @DisplayName("one key per enchantment and level, upper case like an item, the vanilla namespace left out")
    void name() {
        assertThat(new EnchantKey("minecraft:mending", 1).name()).isEqualTo("ENCHANTED_BOOK:MENDING:1");
        assertThat(new EnchantKey("sharpness", 5).name()).isEqualTo("ENCHANTED_BOOK:SHARPNESS:5");
        assertThat(new EnchantKey("mypack:frost_bite", 2).name()).isEqualTo("ENCHANTED_BOOK:MYPACK:FROST_BITE:2");
        assertThat(new EnchantKey("Sharpness", 5).enchantment()).isEqualTo("sharpness");
    }

    @Test
    @DisplayName("read back from what an owner writes, in any case, with or without the namespace")
    void parse() {
        assertThat(EnchantKey.parse("enchanted_book:mending:1")).contains(new EnchantKey("mending", 1));
        assertThat(EnchantKey.parse("ENCHANTED_BOOK:MINECRAFT:SHARPNESS:5")).contains(new EnchantKey("sharpness", 5));
        assertThat(EnchantKey.parse("enchanted_book:mypack:frost_bite:2")).contains(new EnchantKey("mypack:frost_bite", 2));
        assertThat(EnchantKey.parse("ENCHANTED_BOOK")).isEmpty();
        assertThat(EnchantKey.parse("enchanted_book:mending")).isEmpty();
        assertThat(EnchantKey.parse("enchanted_book:mending:0")).isEmpty();
        assertThat(EnchantKey.parse("enchanted_book:mending:x")).isEmpty();
        assertThat(EnchantKey.parse("diamond")).isEmpty();
        assertThat(EnchantKey.parse(null)).isEmpty();
        assertThat(EnchantKey.isKey("ENCHANTED_BOOK:MENDING:1")).isTrue();
        assertThat(EnchantKey.isKey("ENCHANTED_BOOK")).isFalse();
    }

    @Test
    @DisplayName("readable for people and logs")
    void readable() {
        assertThat(new EnchantKey("sharpness", 5).readable()).isEqualTo("Sharpness V");
        assertThat(new EnchantKey("bane_of_arthropods", 4).readable()).isEqualTo("Bane of Arthropods IV");
        assertThat(new EnchantKey("mypack:frost_bite", 2).readable()).isEqualTo("Frost Bite II");
        assertThat(new EnchantKey("luck_of_the_sea", 3).readable()).isEqualTo("Luck of the Sea III");
        assertThat(new EnchantKey("odd", 255).readable()).isEqualTo("Odd 255");
    }
}
