package de.raindancer.modules.arena.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaveMobRulesTest {

    private final WaveMobRule rule = new WaveMobRule();

    @Test
    @DisplayName("only the sun is kept off a fight's mobs: fire aspect, flame, lava and fire still burn them")
    void sunOnly() {
        assertThat(rule.stopsBurning(false, true, false)).as("sunlight on a zombie").isTrue();
        assertThat(rule.stopsBurning(false, true, true)).as("fire aspect, flame, lava").isFalse();
        assertThat(rule.stopsBurning(true, true, false)).as("a player").isFalse();
        assertThat(rule.stopsBurning(false, false, false)).as("outside the arena").isFalse();
    }

    @Test
    @DisplayName("a mob that fights with a weapon gets one when it came without")
    void weapons() {
        assertThat(rule.weaponFor("PIGLIN_BRUTE")).contains("GOLDEN_AXE");
        assertThat(rule.weaponFor("piglin")).contains("GOLDEN_SWORD");
        assertThat(rule.weaponFor("SKELETON")).contains("BOW");
        assertThat(rule.weaponFor("STRAY")).contains("BOW");
        assertThat(rule.weaponFor("WITHER_SKELETON")).contains("STONE_SWORD");
        assertThat(rule.weaponFor("VINDICATOR")).contains("IRON_AXE");
        assertThat(rule.weaponFor("PILLAGER")).contains("CROSSBOW");
        assertThat(rule.weaponFor("ZOMBIE")).as("fists are a zombie's weapon").isEmpty();
    }
}
