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
        assertThat(ParticleDensity.VERY_DENSE.denser()).isEqualTo(ParticleDensity.ULTRA);
        assertThat(ParticleDensity.ULTRA.denser()).isEqualTo(ParticleDensity.ULTRA);
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

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Ultra is for those allowed it: it passes the server's ceiling, and anybody else gets the densest they may have")
    void ultra() {
        var rule = new de.raindancer.modules.cosmetics.rules.ParticleRule();
        var ultra = de.raindancer.modules.cosmetics.model.ParticleDensity.ULTRA;

        org.assertj.core.api.Assertions.assertThat(ultra.count())
                .isGreaterThanOrEqualTo(de.raindancer.core.ui.effect.ParticleShape.ULTRA);
        org.assertj.core.api.Assertions.assertThat(rule.count(ultra, 2, 4)).isEqualTo(ultra.count());
        org.assertj.core.api.Assertions.assertThat(rule.allowed(ultra, false))
                .isEqualTo(de.raindancer.modules.cosmetics.model.ParticleDensity.VERY_DENSE);
        org.assertj.core.api.Assertions.assertThat(rule.allowed(ultra, true)).isEqualTo(ultra);
        org.assertj.core.api.Assertions.assertThat(rule.allowed(null, false)).isNull();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("coloured wings are drawn every tick to live one tick; everything else at the server's pace, its animation unhurried")
    void pace() {
        var rule = new de.raindancer.modules.cosmetics.rules.ParticleRule();

        org.assertj.core.api.Assertions.assertThat(rule.everyTick(ParticleShape.WINGS, true, false)).isTrue();
        org.assertj.core.api.Assertions.assertThat(rule.everyTick(ParticleShape.WINGS, false, false)).isFalse();
        org.assertj.core.api.Assertions.assertThat(rule.everyTick(ParticleShape.HALO, true, false)).isFalse();
        org.assertj.core.api.Assertions.assertThat(rule.everyTick(ParticleShape.WINGS, true, true))
                .as("natural wings are the particle as Minecraft draws it, at the server's pace").isFalse();

        org.assertj.core.api.Assertions.assertThat(rule.drawsNow(7, 4, true)).isTrue();
        org.assertj.core.api.Assertions.assertThat(rule.drawsNow(7, 4, false)).isFalse();
        org.assertj.core.api.Assertions.assertThat(rule.drawsNow(8, 4, false)).isTrue();
        // A halo drawn every fourth tick moves on one frame per draw, as it always did.
        org.assertj.core.api.Assertions.assertThat(rule.animationTick(8, 4, false)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(rule.animationTick(8, 4, true)).isEqualTo(8);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("wings chosen as the particle before wings had their own slot move there, and the particle is free again")
    void wingsMoveToTheirOwnSlot() {
        var rule = new de.raindancer.modules.cosmetics.rules.ParticleRule();
        ParticleChoice wornWings = new ParticleChoice("DUST", ParticleShape.WINGS, 0xFFFFFF);
        ParticleChoice halo = new ParticleChoice("FLAME", ParticleShape.HALO, null);

        var moved = rule.split(wornWings, ParticleChoice.NONE);
        org.assertj.core.api.Assertions.assertThat(moved.particle().isNone()).isTrue();
        org.assertj.core.api.Assertions.assertThat(moved.wings()).isEqualTo(wornWings);

        var kept = rule.split(halo, ParticleChoice.NONE);
        org.assertj.core.api.Assertions.assertThat(kept.particle()).isEqualTo(halo);
        org.assertj.core.api.Assertions.assertThat(kept.wings().isNone()).isTrue();

        ParticleChoice ownWings = new ParticleChoice("FLAME", ParticleShape.DRAGON_WINGS, null);
        var both = rule.split(wornWings, ownWings);
        org.assertj.core.api.Assertions.assertThat(both.wings()).as("wings already chosen are not replaced")
                .isEqualTo(ownWings);
        org.assertj.core.api.Assertions.assertThat(both.particle().isNone()).isTrue();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a choice can be natural — the particle as Minecraft draws it — and keeps that through every change")
    void natural() {
        ParticleChoice crisp = new ParticleChoice("DUST", ParticleShape.WINGS, 0xFFFFFF);
        org.assertj.core.api.Assertions.assertThat(crisp.natural()).isFalse();
        ParticleChoice natural = crisp.withNatural(true);
        org.assertj.core.api.Assertions.assertThat(natural.withColour(0x112233).withShape(ParticleShape.PHOENIX_WINGS)
                .natural()).isTrue();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("wings nobody chose a density for are drawn Dense, as their page says — not at the server's light default")
    void wingsDefaultToDense() {
        var rule = new ParticleRule();
        org.assertj.core.api.Assertions.assertThat(rule.wingDensity(null)).isEqualTo(ParticleDensity.DENSE);
        org.assertj.core.api.Assertions.assertThat(rule.wingDensity(ParticleDensity.ULTRA)).isEqualTo(ParticleDensity.ULTRA);
    }
}
