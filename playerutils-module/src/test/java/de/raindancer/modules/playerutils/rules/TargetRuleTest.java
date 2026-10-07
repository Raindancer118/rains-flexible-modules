package de.raindancer.modules.playerutils.rules;

import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may point what at whom. */
class TargetRuleTest {

    private final TargetRule rule = new TargetRule();

    private static TargetRule.Asker asker(String... nodes) {
        Set<String> has = Set.of(nodes);
        return new TargetRule.Asker(false, has::contains);
    }

    @Test
    @DisplayName("yourself needs the node; somebody else needs .others as well")
    void selfAndOthers() {
        assertThat(rule.judge(Action.HEAL, asker("rainsplayerutils.heal"), true, node -> false).allowed()).isTrue();
        assertThat(rule.judge(Action.HEAL, asker("rainsplayerutils.heal"), false, node -> false).key())
                .isEqualTo("playerutils.no-permission-others");
        assertThat(rule.judge(Action.HEAL, asker(), true, node -> false).key())
                .isEqualTo("playerutils.no-permission");
    }

    @Test
    @DisplayName("an exempt player cannot be hurt by somebody else, unless the asker may bypass it")
    void exempt() {
        TargetRule.Asker staff = asker("rainsplayerutils.damage", "rainsplayerutils.damage.others");
        assertThat(rule.judge(Action.DAMAGE, staff, false, TargetRule.EXEMPT::equals).key())
                .isEqualTo("playerutils.exempt");
        assertThat(rule.judge(Action.HEAL, asker("rainsplayerutils.heal", "rainsplayerutils.heal.others"),
                false, TargetRule.EXEMPT::equals).allowed())
                .as("healing an exempt player is fine — exemption is from harm, not from help")
                .isTrue();
        TargetRule.Asker bypass = asker("rainsplayerutils.damage", "rainsplayerutils.damage.others",
                TargetRule.BYPASS);
        assertThat(rule.judge(Action.DAMAGE, bypass, false, TargetRule.EXEMPT::equals).allowed()).isTrue();
    }

    @Test
    @DisplayName("the console may do anything to anybody")
    void console() {
        assertThat(rule.judge(Action.WIPE, TargetRule.Asker.CONSOLE, false, TargetRule.EXEMPT::equals).allowed())
                .isTrue();
    }

    @Test
    @DisplayName("spectating or sudoing yourself makes no sense and says so")
    void neverSelf() {
        assertThat(rule.judge(Action.SPECTATE, asker("rainsplayerutils.spectate"), true, node -> false).key())
                .isEqualTo("playerutils.not-on-yourself");
    }

    @Test
    @DisplayName("information about yourself needs nobody's permission unless the server takes it away")
    void infoOnSelf() {
        assertThat(rule.judge(Action.PING, asker("rainsplayerutils.ping"), true, node -> false).allowed()).isTrue();
    }
}
