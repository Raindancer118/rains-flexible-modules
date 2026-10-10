package de.raindancer.modules.roles.rules;

import de.raindancer.modules.roles.model.Ability;
import de.raindancer.modules.roles.model.AbilityKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AbilityRuleTest {

    private final AbilityRule rule = new AbilityRule();

    @Test
    @DisplayName("hunger drains slower, falls and monsters hurt less and more, by the percent")
    void scales() {
        assertThat(rule.less(4.0, 20)).isCloseTo(3.2, within(1e-9));
        assertThat(rule.more(10.0, 10)).isCloseTo(11.0, within(1e-9));
        assertThat(rule.less(4.0, 0)).isEqualTo(4.0);
        assertThat(rule.less(4.0, 250)).as("never below nothing").isEqualTo(0.0);
    }

    @Test
    @DisplayName("a chance is taken when the roll falls under it")
    void chance() {
        assertThat(rule.happens(20, 0.19)).isTrue();
        assertThat(rule.happens(20, 0.2)).isFalse();
        assertThat(rule.happens(0, 0.0)).isFalse();
    }

    @Test
    @DisplayName("more experience rounds the leftover fairly: 10 at 15% is 11 or 12, 11.5 on average")
    void experience() {
        assertThat(rule.experience(10, 15, 0.49)).isEqualTo(12);
        assertThat(rule.experience(10, 15, 0.51)).isEqualTo(11);
        assertThat(rule.experience(0, 15, 0.0)).isZero();
        assertThat(rule.experience(-3, 15, 0.0)).as("experience taken away is left alone").isEqualTo(-3);
    }

    @Test
    @DisplayName("ore luck is for ore that drops something else: not a silk-touched block, not stone")
    void fortune() {
        assertThat(rule.lucky("IRON_ORE", "RAW_IRON")).isTrue();
        assertThat(rule.lucky("DEEPSLATE_DIAMOND_ORE", "DIAMOND")).isTrue();
        assertThat(rule.lucky("NETHER_QUARTZ_ORE", "QUARTZ")).isTrue();
        assertThat(rule.lucky("IRON_ORE", "IRON_ORE")).as("silk touch").isFalse();
        assertThat(rule.lucky("STONE", "COBBLESTONE")).isFalse();
        assertThat(rule.lucky("ANCIENT_DEBRIS", "ANCIENT_DEBRIS")).isFalse();
    }

    @Test
    @DisplayName("every kind has a ceiling that keeps it a flavour, whatever roles.yml says")
    void ceilings() {
        for (AbilityKind kind : AbilityKind.values()) {
            assertThat(kind.most()).as(kind.name()).isBetween(5, 40);
        }
        assertThat(new Ability(AbilityKind.SPEED, 80, null).percent()).isEqualTo(AbilityKind.SPEED.most());
        assertThat(new Ability(AbilityKind.SPEED, -5, null).percent()).isZero();
    }

    @Test
    @DisplayName("what an ability says, at the size somebody has it now")
    void says() {
        assertThat(new Ability(AbilityKind.HUNGER, 20, null).says(8)).isEqualTo("Hunger drains 8% slower");
        assertThat(new Ability(AbilityKind.MONSTERS, 10, null).says(10)).isEqualTo("10% more damage to monsters");
        assertThat(new Ability(AbilityKind.BUTCHER, 20, null).says(20)).isEqualTo("20% chance of extra food from animals");
        assertThat(new Ability(AbilityKind.FORTUNE, 10, null).says(10)).isEqualTo("10% chance of an extra drop from ore");
    }
}
