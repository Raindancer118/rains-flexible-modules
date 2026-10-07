package de.raindancer.modules.cosmetics;

import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.model.ParticleDensity;
import de.raindancer.modules.cosmetics.model.ParticleSpeed;
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

    @Test
    @DisplayName("a chosen density sets how many are drawn, never past the server's ceiling")
    void density() {
        assertThat(rule.count(ParticleDensity.DENSE, 1, 6)).isEqualTo(4);
        assertThat(rule.count(ParticleDensity.VERY_DENSE, 1, 3)).as("capped by the owner").isEqualTo(3);
        assertThat(rule.count(null, 2, 6)).as("nothing chosen: the server's default").isEqualTo(2);
        assertThat(rule.count(ParticleDensity.LIGHT, 1, 0)).as("never nothing at all").isEqualTo(1);
    }

    @Test
    @DisplayName("densities step up and down, and stop at the ends")
    void stepping() {
        assertThat(ParticleDensity.LIGHT.denser()).isEqualTo(ParticleDensity.NORMAL);
        assertThat(ParticleDensity.VERY_DENSE.denser()).isEqualTo(ParticleDensity.VERY_DENSE);
        assertThat(ParticleDensity.NORMAL.lighter()).isEqualTo(ParticleDensity.LIGHT);
        assertThat(ParticleDensity.LIGHT.lighter()).isEqualTo(ParticleDensity.LIGHT);
        assertThat(ParticleDensity.of("very_dense")).contains(ParticleDensity.VERY_DENSE);
        assertThat(ParticleDensity.of("thick")).isEmpty();
    }

    @Test
    @DisplayName("a choice keeps its density")
    void choiceKeepsDensity() {
        ParticleChoice flame = new ParticleChoice("flame", ParticleShape.HALO, null).withDensity(ParticleDensity.DENSE);
        assertThat(flame.density()).isEqualTo(ParticleDensity.DENSE);
        assertThat(flame.withShape(ParticleShape.AURA).density()).isEqualTo(ParticleDensity.DENSE);
    }

    @Test
    @DisplayName("speed sets how fast the shape moves: slow lingers, fast skips ahead")
    void speed() {
        assertThat(rule.frame(100, ParticleSpeed.NORMAL)).isEqualTo(100);
        assertThat(rule.frame(100, ParticleSpeed.SLOW)).isEqualTo(50);
        assertThat(rule.frame(100, ParticleSpeed.FAST)).isEqualTo(200);
        assertThat(rule.frame(100, null)).as("nothing chosen: normal").isEqualTo(100);
        assertThat(ParticleSpeed.SLOW.faster()).isEqualTo(ParticleSpeed.NORMAL);
        assertThat(ParticleSpeed.VERY_FAST.faster()).isEqualTo(ParticleSpeed.VERY_FAST);
        assertThat(ParticleSpeed.of("very_fast")).contains(ParticleSpeed.VERY_FAST);
    }

    @Test
    @DisplayName("a choice keeps its speed through other changes")
    void choiceKeepsSpeed() {
        ParticleChoice flame = new ParticleChoice("flame", ParticleShape.HALO, null).withSpeed(ParticleSpeed.FAST)
                .withDensity(ParticleDensity.DENSE);
        assertThat(flame.speed()).isEqualTo(ParticleSpeed.FAST);
        assertThat(flame.withShape(ParticleShape.AURA).speed()).isEqualTo(ParticleSpeed.FAST);
    }
}
