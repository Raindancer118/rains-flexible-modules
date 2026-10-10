package de.raindancer.modules.economy.rules;

import de.raindancer.modules.economy.model.EnchantLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EnchantWorthRuleTest {

    private final EnchantWorthRule worth = new EnchantWorthRule(List.of());

    private static EnchantLevel max(String key, int maxLevel) {
        return new EnchantLevel(key, maxLevel, maxLevel, false, false);
    }

    @Test
    @DisplayName("the useful ones are worth more at their best than the niche ones")
    void ranked() {
        assertThat(worth.worth(new EnchantLevel("mending", 1, 1, true, false)))
                .isGreaterThan(worth.worth(max("protection", 4)));
        assertThat(worth.worth(max("protection", 4))).isGreaterThan(worth.worth(max("fire_protection", 4)));
        assertThat(worth.worth(max("efficiency", 5))).isGreaterThan(worth.worth(max("lure", 3)));
        assertThat(worth.worth(max("sharpness", 5))).isGreaterThan(worth.worth(max("smite", 5)));
        assertThat(worth.worth(max("smite", 5))).isGreaterThan(worth.worth(max("bane_of_arthropods", 5)));
        assertThat(worth.worth(max("unbreaking", 3))).isGreaterThan(worth.worth(max("knockback", 2)));
    }

    @Test
    @DisplayName("a level is its share of the enchantment's best: Protection I is a quarter of Protection IV")
    void perLevel() {
        double full = worth.worth(max("protection", 4));
        assertThat(worth.worth(new EnchantLevel("protection", 1, 4, false, false))).isCloseTo(full / 4, within(1e-9));
        assertThat(worth.worth(new EnchantLevel("protection", 2, 4, false, false))).isCloseTo(full / 2, within(1e-9));
    }

    @Test
    @DisplayName("an enchantment nobody ranked is worth a level per level, treasure double — as before")
    void unknown() {
        assertThat(worth.worth(new EnchantLevel("someplugin_magic", 2, 3, false, false))).isCloseTo(2, within(1e-9));
        assertThat(worth.worth(new EnchantLevel("someplugin_relic", 1, 1, true, false))).isCloseTo(2, within(1e-9));
    }

    @Test
    @DisplayName("curses take value away")
    void curses() {
        assertThat(worth.worth(new EnchantLevel("vanishing_curse", 1, 1, true, true))).isNegative();
        assertThat(worth.worth(new EnchantLevel("binding_curse", 1, 1, true, true))).isNegative();
    }

    @Test
    @DisplayName("an owner can rank any enchantment themselves; lines that make no sense are skipped")
    void overrides() {
        EnchantWorthRule own = new EnchantWorthRule(List.of("mending 20", "Bane_Of_Arthropods=9", "nonsense", "sharpness -3",
                "someplugin_magic 6"));
        assertThat(own.worth(new EnchantLevel("mending", 1, 1, true, false))).isCloseTo(20, within(1e-9));
        assertThat(own.worth(max("bane_of_arthropods", 5))).isCloseTo(9, within(1e-9));
        assertThat(own.worth(max("sharpness", 5))).as("negative skipped").isEqualTo(worth.worth(max("sharpness", 5)));
        assertThat(own.worth(new EnchantLevel("someplugin_magic", 3, 3, false, false))).isCloseTo(6, within(1e-9));
    }

    @Test
    @DisplayName("the best of an enchantment is what the shop's drawer is sorted by")
    void best() {
        assertThat(worth.best("mending", 1, true, false)).isGreaterThan(worth.best("protection", 4, false, false));
        assertThat(worth.best("vanishing_curse", 1, true, true)).isNegative();
    }
}
