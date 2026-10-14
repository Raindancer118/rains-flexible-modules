package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OffenceTest {

    @Test
    @DisplayName("Overpower adds 1.5 per level on top of the hit, Executioner and Berserk add percent")
    void hit() {
        Offence.Hit plain = new Offence.Hit(10, 0, 0, 0, 20, 20, 20, 20, 0, 0, false);
        assertThat(Offence.damage(plain)).isCloseTo(10, within(1e-9));

        Offence.Hit overpowered = new Offence.Hit(10, 5, 0, 0, 20, 20, 20, 20, 0, 0, false);
        assertThat(Offence.damage(overpowered)).isCloseTo(17.5, within(1e-9));

        Offence.Hit execution = new Offence.Hit(10, 0, 3, 0, 5, 20, 20, 20, 0, 0, false);
        assertThat(Offence.damage(execution)).as("below 30 %: +45 %").isCloseTo(14.5, within(1e-9));
        Offence.Hit healthy = new Offence.Hit(10, 0, 3, 0, 7, 20, 20, 20, 0, 0, false);
        assertThat(Offence.damage(healthy)).as("above 30 % nothing").isCloseTo(10, within(1e-9));

        Offence.Hit rage = new Offence.Hit(10, 0, 0, 3, 20, 20, 5, 20, 0, 0, false);
        assertThat(Offence.damage(rage)).as("three quarters down: 70 % x 0.75").isCloseTo(15.25, within(1e-9));
    }

    @Test
    @DisplayName("against a player every bonus counts half")
    void pvp() {
        Offence.Hit mob = new Offence.Hit(10, 4, 0, 0, 20, 20, 20, 20, 0, 0, false);
        Offence.Hit player = new Offence.Hit(10, 4, 0, 0, 20, 20, 20, 20, 0, 0, true);
        assertThat(Offence.damage(mob)).isCloseTo(16, within(1e-9));
        assertThat(Offence.damage(player)).isCloseTo(13, within(1e-9));
        assertThat(Offence.lifesteal(3, 10, false)).isCloseTo(1.4, within(1e-9));
        assertThat(Offence.lifesteal(3, 10, true)).isCloseTo(0.7, within(1e-9));
        assertThat(Offence.lifesteal(3, 1000, false)).as("never more than three hearts a hit").isEqualTo(6);
    }

    @Test
    @DisplayName("Armor Pierce undoes a share of what armour takes off: 15 % a level")
    void pierce() {
        double armour = 20;
        double toughness = 8;
        double taken = Offence.armourShare(10, armour, toughness);
        assertThat(taken).isCloseTo(0.8 - 0.0, within(0.2));
        double extra = Offence.pierce(10, armour, toughness, 2, false);
        double after = (10 + extra) * (1 - Offence.armourShare(10 + extra, armour, toughness));
        double wanted = 10 * (1 - taken) + 0.3 * 10 * taken;
        assertThat(after).isCloseTo(wanted, within(1e-6));
        assertThat(Offence.pierce(10, 0, 0, 3, false)).as("nothing to pierce").isZero();
        assertThat(Offence.pierce(10, armour, toughness, 2, true)).isLessThan(extra);
    }

    @Test
    @DisplayName("Bleed stacks up to two a level, half a heart a second each; Cleave reaches more, harder, each level")
    void bleedAndCleave() {
        assertThat(Offence.bleedStacks(1, 5)).isEqualTo(2);
        assertThat(Offence.bleedStacks(3, 1)).isEqualTo(2);
        assertThat(Offence.bleedStacks(3, 9)).isEqualTo(6);
        assertThat(Offence.bleedPerSecond(4, false)).isEqualTo(2.0);
        assertThat(Offence.bleedPerSecond(4, true)).isEqualTo(1.0);
        assertThat(Offence.cleaveTargets(1)).isEqualTo(2);
        assertThat(Offence.cleaveTargets(3)).isEqualTo(4);
        assertThat(Offence.cleaveShare(3, false)).isCloseTo(0.6, within(1e-9));
        assertThat(Offence.cleaveShare(3, true)).isCloseTo(0.3, within(1e-9));
    }

    @Test
    @DisplayName("Venom: poison, stronger and longer each level, wither on top at III; half as long on a player")
    void venom() {
        assertThat(Offence.venom(1, false)).containsExactly(new Offence.Dose("poison", 0, 80));
        assertThat(Offence.venom(2, false)).containsExactly(new Offence.Dose("poison", 1, 80));
        assertThat(Offence.venom(3, false)).containsExactly(new Offence.Dose("poison", 1, 100),
                new Offence.Dose("wither", 0, 60));
        assertThat(Offence.venom(3, true)).containsExactly(new Offence.Dose("poison", 1, 50),
                new Offence.Dose("wither", 0, 30));
    }
}
