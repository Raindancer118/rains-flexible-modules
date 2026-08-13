package de.raindancer.modules.claims.screen;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A middle click never reaches the server for a survival player — Bukkit only ever delivers
 * {@code ClickType.MIDDLE} to a creative-mode client picking a block. {@link EffectsMenu}'s particle
 * toggle used to gate on exactly that click type, so it silently did nothing for anyone but an operator
 * in creative. This pins the fix to {@code DROP}/{@code CONTROL_DROP} (the Q key), which fire for every
 * game mode, and pins {@code MIDDLE} to no longer being the trigger at all.
 */
class EffectsMenuTest {

    @ParameterizedTest
    @EnumSource(value = ClickType.class, names = {"DROP", "CONTROL_DROP"})
    void dropAndControlDropToggleParticles(ClickType click) {
        assertThat(EffectsMenu.togglesParticles(click)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ClickType.class, names = {"DROP", "CONTROL_DROP"}, mode = EnumSource.Mode.EXCLUDE)
    void everyOtherClickLeavesParticlesAlone(ClickType click) {
        assertThat(EffectsMenu.togglesParticles(click)).isFalse();
    }
}
