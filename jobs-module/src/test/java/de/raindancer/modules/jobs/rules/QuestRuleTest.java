package de.raindancer.modules.jobs.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.model.QuestTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QuestRuleTest {

    private final QuestRule rule = new QuestRule();
    private final Money step = Money.of(5_000);

    @Test
    @DisplayName("the tier steps up at every doubling of wealth past the first step, and stops at the top")
    void tiers() {
        assertThat(rule.tier(Money.of(0), step, 8)).isZero();
        assertThat(rule.tier(Money.of(4_999), step, 8)).isZero();
        assertThat(rule.tier(Money.of(5_000), step, 8)).isEqualTo(1);
        assertThat(rule.tier(Money.of(15_000), step, 8)).isEqualTo(2);
        assertThat(rule.tier(Money.of(35_000), step, 8)).isEqualTo(3);
        assertThat(rule.tier(Money.of(1_000_000_000), step, 8)).isEqualTo(8);
        assertThat(rule.tier(Money.of(-50), step, 8)).as("debt is the lowest tier").isZero();
        assertThat(rule.tier(Money.of(99_999), Money.ZERO, 8)).as("no step: one tier for all").isZero();
    }

    @Test
    @DisplayName("a richer player's quest asks more, and pays more for each thing asked, so it stays worth it")
    void harderAndBetterPaid() {
        int poor = rule.amount(20, 0, 35);
        int rich = rule.amount(20, 4, 35);
        Money poorPay = rule.pay(Money.of(150), 0, 60, 0, 100);
        Money richPay = rule.pay(Money.of(150), 4, 60, 0, 100);
        assertThat(poor).isEqualTo(20);
        assertThat(rich).isEqualTo(66);
        assertThat(poorPay).isEqualTo(Money.of(150));
        assertThat(richPay).isEqualTo(Money.of(983));
        assertThat(richPay.minor() / (double) rich).as("pay per thing grows").isGreaterThan(poorPay.minor() / (double) poor);
    }

    @Test
    @DisplayName("a role's own quests pay its bonus, the owner's scale applies, and nothing positive pays nothing")
    void bonusAndScale() {
        assertThat(rule.pay(Money.of(200), 0, 60, 25, 100)).isEqualTo(Money.of(250));
        assertThat(rule.pay(Money.of(200), 0, 60, 0, 50)).isEqualTo(Money.of(100));
        assertThat(rule.pay(Money.of(200), 0, 60, 0, 0)).isEqualTo(Money.ZERO);
        assertThat(rule.pay(Money.of(1), 0, 60, 0, 10)).as("never rounds a paying quest to nothing").isEqualTo(Money.of(1));
    }

    private static QuestTemplate quest(String id, String role) {
        return new QuestTemplate(id, id, "paper", QuestTask.KILL, ItemSelection.parse(List.of("zombie")), role, 5, "100");
    }

    @Test
    @DisplayName("a day is some quests for anybody and some of the player's own role; none of another role's")
    void picks() {
        List<QuestTemplate> all = List.of(quest("a", ""), quest("b", ""), quest("c", ""), quest("d", ""),
                quest("m1", "miner"), quest("m2", "miner"), quest("m3", "miner"), quest("h1", "hunter"));
        List<QuestTemplate> day = rule.pick(all, "miner", 3, 2, Set.of(), new Random(1));
        assertThat(day).hasSize(5).doesNotHaveDuplicates();
        assertThat(day).filteredOn(QuestTemplate::forRole).hasSize(2).allMatch(each -> each.role().equals("miner"));
        assertThat(rule.pick(all, "", 3, 2, Set.of(), new Random(1))).as("no role, no role quests")
                .hasSize(3).noneMatch(QuestTemplate::forRole);
    }

    @Test
    @DisplayName("yesterday's quests come back only when there are not enough others")
    void notTheSameAgain() {
        List<QuestTemplate> all = List.of(quest("a", ""), quest("b", ""), quest("c", ""), quest("d", ""));
        for (int seed = 0; seed < 20; seed++) {
            assertThat(rule.pick(all, "", 2, 0, Set.of("a", "b"), new Random(seed)))
                    .extracting(QuestTemplate::id).containsExactlyInAnyOrder("c", "d");
        }
        assertThat(rule.pick(all, "", 3, 0, Set.of("a", "b"), new Random(3)))
                .extracting(QuestTemplate::id).contains("c", "d");
    }
}
