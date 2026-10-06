package de.raindancer.modules.cosmetics;

import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.rules.ParticleRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may wear which particle, and when a worn one is drawn at all. */
class ParticleRuleTest {

    private final ParticleRule rule = new ParticleRule();
    private static final Set<String> BLOCKED = Set.of("ELDER_GUARDIAN");

    @Test
    @DisplayName("a vanilla particle that can be drawn is allowed")
    void allowed() {
        assertThat(rule.judge("flame", true, BLOCKED, true).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("each refusal has its own reason")
    void refusals() {
        assertThat(rule.judge("FLAME", false, BLOCKED, true).reason()).isEqualTo("cosmetics.refused.particles");
        assertThat(rule.judge("BLOCK", true, BLOCKED, false).reason())
                .isEqualTo("cosmetics.refused.particle-unknown");
        assertThat(rule.judge("elder_guardian", true, BLOCKED, true).reason())
                .as("the owner's blocklist, in any case").isEqualTo("cosmetics.refused.particle-blocked");
    }

    @Test
    @DisplayName("nothing is drawn for somebody who should not be seen")
    void hiddenPeopleShowNothing() {
        assertThat(rule.shows(true, false, false, false, false)).isTrue();
        assertThat(rule.shows(false, false, false, false, false)).as("switched off server-wide").isFalse();
        assertThat(rule.shows(true, true, false, false, false)).as("vanished").isFalse();
        assertThat(rule.shows(true, false, true, false, false)).as("spectating").isFalse();
        assertThat(rule.shows(true, false, false, true, false)).as("invisible").isFalse();
        assertThat(rule.shows(true, false, false, false, true)).as("dead").isFalse();
    }

    @Test
    @DisplayName("a choice keeps its shape and colour, and none is nothing")
    void choices() {
        ParticleChoice flame = new ParticleChoice("flame", ParticleShape.HALO, null);
        assertThat(flame.particle()).isEqualTo("FLAME");
        assertThat(flame.withShape(ParticleShape.TRAIL).shape()).isEqualTo(ParticleShape.TRAIL);
        assertThat(flame.withColour(0xff0000).colour()).isEqualTo(0xff0000);
        assertThat(ParticleChoice.NONE.isNone()).isTrue();
        assertThat(flame.isNone()).isFalse();
    }
}
