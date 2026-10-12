package de.raindancer.modules.arena.rules;

import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Verdict;
import de.raindancer.modules.arena.model.ArenaWorld;
import de.raindancer.modules.arena.model.Box;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RulesTest {

    // ── the border ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the border is square round the map's middle, as wide as its longer side plus a margin each way")
    void border() {
        ArenaWorld world = new ArenaWorld("arena", "b.schem", new Box("arena", 0, 64, 0, 230, 127, 195));

        BorderRule.Border border = new BorderRule().border(world, 6);

        assertThat(border.centreX()).isEqualTo(115.5);
        assertThat(border.centreZ()).isEqualTo(98.0);
        assertThat(border.size()).isEqualTo(231 + 12);
    }

    @Test
    @DisplayName("a negative margin is no margin, not a border that cuts into the map")
    void borderNeverCutsTheMap() {
        ArenaWorld world = new ArenaWorld("arena", "a.schem", new Box("arena", 0, 64, 0, 99, 100, 49));

        assertThat(new BorderRule().border(world, -20).size()).isEqualTo(100);
    }

    private static Attack attack(Attack.Fighter attacker, Attack.Fighter victim, String world) {
        Attack.At at = new Attack.At(0, 64, 0);
        return new Attack(attacker, UUID.randomUUID(), victim, UUID.randomUUID(), world, false, at, at);
    }

    @Test
    @DisplayName("outside a fight nobody in the arena can be hurt by anybody, and nobody there can hurt anything")
    void nobodyHurtOutsideAFight() {
        FightRule rule = new FightRule();

        assertThat(rule.verdict(attack(Attack.Fighter.PLAYER, Attack.Fighter.PLAYER, "arena"), "arena"::equals,
                (attacker, victim) -> false)).isEqualTo(Verdict.PROTECTED);
        assertThat(rule.verdict(attack(Attack.Fighter.MOB, Attack.Fighter.PLAYER, "arena"), "arena"::equals,
                (attacker, victim) -> false)).isEqualTo(Verdict.PROTECTED);
        assertThat(rule.verdict(attack(Attack.Fighter.PLAYER, Attack.Fighter.MOB, "arena"), "arena"::equals,
                (attacker, victim) -> false)).isEqualTo(Verdict.PROTECTED);
    }

    @Test
    @DisplayName("two who are fighting each other may, and the arena has no opinion about anywhere else")
    void fightersMay() {
        FightRule rule = new FightRule();

        assertThat(rule.verdict(attack(Attack.Fighter.PLAYER, Attack.Fighter.PLAYER, "arena"), "arena"::equals,
                (attacker, victim) -> true)).isEqualTo(Verdict.ALLOWED);
        assertThat(rule.verdict(attack(Attack.Fighter.PLAYER, Attack.Fighter.PLAYER, "world"), "arena"::equals,
                (attacker, victim) -> false)).isNull();
        assertThat(rule.verdict(attack(Attack.Fighter.MOB, Attack.Fighter.MOB, "arena"), "arena"::equals,
                (attacker, victim) -> false)).as("mobs fighting mobs are not the arena's business").isNull();
    }

    // ── what may be used while aiming at a block ───────────────────────────────────────────────────
}
