package de.raindancer.modules.ctf.rules;

import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("who may hurt whom in the Capture the Flag world")
class CombatRuleTest {

    private static final UUID RED = UUID.nameUUIDFromBytes("red".getBytes());
    private static final UUID RED_TOO = UUID.nameUUIDFromBytes("red2".getBytes());
    private static final UUID BLUE = UUID.nameUUIDFromBytes("blue".getBytes());
    private static final UUID WATCHER = UUID.nameUUIDFromBytes("watcher".getBytes());
    private static final Map<UUID, Integer> SIDES = Map.of(RED, 0, RED_TOO, 0, BLUE, 1);

    private final CombatRule rule = new CombatRule();

    private Verdict judge(Attack attack, boolean friendlyFire) {
        return rule.verdict(attack, "ctf_arena"::equals, id -> Optional.ofNullable(SIDES.get(id)), friendlyFire);
    }

    @Test
    @DisplayName("the other side: yes")
    void enemies() {
        assertThat(judge(Attack.between(RED, BLUE, "ctf_arena"), false)).isEqualTo(Verdict.ALLOWED);
    }

    @Test
    @DisplayName("your own side: only with friendly fire")
    void friends() {
        assertThat(judge(Attack.between(RED, RED_TOO, "ctf_arena"), false)).isEqualTo(Verdict.PROTECTED);
        assertThat(judge(Attack.between(RED, RED_TOO, "ctf_arena"), true)).isEqualTo(Verdict.ALLOWED);
    }

    @Test
    @DisplayName("anybody not playing neither hurts nor is hurt")
    void outsiders() {
        assertThat(judge(Attack.between(WATCHER, BLUE, "ctf_arena"), true)).isEqualTo(Verdict.PROTECTED);
        assertThat(judge(Attack.between(RED, WATCHER, "ctf_arena"), true)).isEqualTo(Verdict.PROTECTED);
    }

    @Test
    @DisplayName("other worlds are not Capture the Flag's business")
    void elsewhere() {
        assertThat(judge(Attack.between(RED, BLUE, "world"), false)).isNull();
    }
}
