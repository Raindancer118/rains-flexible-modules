package de.raindancer.modules.economy.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PotionKeyTest {

    @Test
    @DisplayName("one key per kind of bottle and potion type, upper case like an item, the vanilla namespace left out")
    void name() {
        assertThat(new PotionKey("POTION", "minecraft:strong_healing").name()).isEqualTo("POTION:STRONG_HEALING");
        assertThat(new PotionKey("splash_potion", "Swiftness").name()).isEqualTo("SPLASH_POTION:SWIFTNESS");
        assertThat(new PotionKey("LINGERING_POTION", "mypack:foo").name()).isEqualTo("LINGERING_POTION:MYPACK:FOO");
        assertThat(PotionKey.ominous(3).name()).isEqualTo("OMINOUS_BOTTLE:3");
    }

    @Test
    @DisplayName("read back from what an owner writes, in any case")
    void parse() {
        assertThat(PotionKey.parse("potion:strong_healing")).contains(new PotionKey("POTION", "strong_healing"));
        assertThat(PotionKey.parse("SPLASH_POTION:MINECRAFT:LONG_SWIFTNESS"))
                .contains(new PotionKey("SPLASH_POTION", "long_swiftness"));
        assertThat(PotionKey.parse("lingering_potion:mypack:foo")).contains(new PotionKey("LINGERING_POTION", "mypack:foo"));
        assertThat(PotionKey.parse("ominous_bottle:5")).contains(PotionKey.ominous(5));
        assertThat(PotionKey.parse("ominous_bottle:0")).isEmpty();
        assertThat(PotionKey.parse("ominous_bottle:6")).isEmpty();
        assertThat(PotionKey.parse("ominous_bottle:x")).isEmpty();
        assertThat(PotionKey.parse("POTION")).isEmpty();
        assertThat(PotionKey.parse("potion:")).isEmpty();
        assertThat(PotionKey.parse("diamond")).isEmpty();
        assertThat(PotionKey.parse("ENCHANTED_BOOK:MENDING:1")).isEmpty();
        assertThat(PotionKey.parse(null)).isEmpty();
        assertThat(PotionKey.isKey("POTION:HEALING")).isTrue();
        assertThat(PotionKey.isKey("POTION")).isFalse();
    }

    @Test
    @DisplayName("the item a key is sold as")
    void item() {
        assertThat(new PotionKey("SPLASH_POTION", "healing").item()).isEqualTo("SPLASH_POTION");
        assertThat(PotionKey.ominous(2).item()).isEqualTo("OMINOUS_BOTTLE");
        assertThat(PotionKey.ominous(2).isOminous()).isTrue();
        assertThat(PotionKey.ominous(2).level()).isEqualTo(2);
        assertThat(new PotionKey("POTION", "healing").isOminous()).isFalse();
    }

    @Test
    @DisplayName("readable for people and logs")
    void readable() {
        assertThat(new PotionKey("POTION", "healing").readable()).isEqualTo("Potion of Healing");
        assertThat(new PotionKey("POTION", "strong_healing").readable()).isEqualTo("Potion of Healing II");
        assertThat(new PotionKey("SPLASH_POTION", "long_night_vision").readable())
                .isEqualTo("Splash Potion of Night Vision (long)");
        assertThat(new PotionKey("LINGERING_POTION", "turtle_master").readable())
                .isEqualTo("Lingering Potion of the Turtle Master");
        assertThat(new PotionKey("POTION", "water").readable()).isEqualTo("Water Bottle");
        assertThat(new PotionKey("SPLASH_POTION", "awkward").readable()).isEqualTo("Splash Awkward Potion");
        assertThat(new PotionKey("POTION", "mypack:foo_bar").readable()).isEqualTo("Potion of Foo Bar");
        assertThat(PotionKey.ominous(3).readable()).isEqualTo("Ominous Bottle (Bad Omen III)");
    }
}
