package de.raindancer.modules.essentials.rules;

import de.raindancer.modules.essentials.model.Wear;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepairRuleTest {

    private final RepairRule rule = new RepairRule();

    private static RepairRule.Request hand(Wear wear, boolean self, boolean mayOthers) {
        return new RepairRule.Request(RepairRule.Scope.HAND, self, mayOthers, false, wear);
    }

    @Test
    @DisplayName("a damaged tool is repairable")
    void damaged() {
        assertThat(rule.judge(hand(new Wear(true, true, 40), true, false)).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("empty hands, a block and a mended tool each get their own sentence")
    void eachNothingHasItsOwnReason() {
        assertThat(rule.judge(hand(Wear.EMPTY, true, false)).reason()).isEqualTo("essentials.repair.nothing-held");
        assertThat(rule.judge(hand(new Wear(true, false, 0), true, false)).reason())
                .isEqualTo("essentials.repair.not-damageable");
        assertThat(rule.judge(hand(new Wear(true, true, 0), true, false)).reason())
                .isEqualTo("essentials.repair.already-fine");
    }

    @Test
    @DisplayName("somebody else's needs the others node")
    void others() {
        assertThat(rule.judge(hand(new Wear(true, true, 5), false, false)).reason())
                .isEqualTo("essentials.repair.not-others");
        assertThat(rule.judge(hand(new Wear(true, true, 5), false, true)).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("the whole inventory needs its own node, on top of the single item one")
    void all() {
        RepairRule.Request noNode = new RepairRule.Request(RepairRule.Scope.ALL, true, false, false, Wear.EMPTY);
        RepairRule.Request withNode = new RepairRule.Request(RepairRule.Scope.ALL, true, false, true, Wear.EMPTY);
        assertThat(rule.judge(noNode).reason()).isEqualTo("essentials.repair.not-all");
        assertThat(rule.judge(withNode).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("only an item that can wear and is worn counts as needing repair")
    void needsRepair() {
        assertThat(RepairRule.needsRepair(new Wear(true, true, 1))).isTrue();
        assertThat(RepairRule.needsRepair(new Wear(true, true, 0))).isFalse();
        assertThat(RepairRule.needsRepair(new Wear(true, false, 3))).isFalse();
        assertThat(RepairRule.needsRepair(Wear.EMPTY)).isFalse();
    }
}
