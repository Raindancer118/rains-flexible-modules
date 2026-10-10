package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import io.papermc.paper.advancement.AdvancementDisplay.Frame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BackpayRuleTest {

    private final BackpayRule rule = new BackpayRule();

    private static Money price(Frame frame) {
        return switch (frame) {
            case GOAL -> Money.of(750);
            case CHALLENGE -> Money.of(2000);
            default -> Money.of(250);
        };
    }

    @Test
    @DisplayName("advancements never paid are owed in full; ones paid the old flat 250 are topped up; ones paid in full not at all")
    void owed() {
        var made = List.of(new BackpayRule.Made("Stone Age", Frame.TASK), new BackpayRule.Made("Hot Stuff", Frame.TASK),
                new BackpayRule.Made("The End?", Frame.GOAL), new BackpayRule.Made("Return to Sender", Frame.CHALLENGE));
        var paid = Map.of("Hot Stuff", Money.of(250), "Return to Sender", Money.of(250));
        BackpayRule.Owed owed = rule.owed(made, paid, BackpayRuleTest::price);
        assertThat(owed.total()).isEqualTo(Money.of(250 + 750 + 1750));
        assertThat(owed.count()).isEqualTo(3);
        assertThat(owed.lines()).as("each one on its own, under its own title, so the ledger knows it was paid")
                .containsExactly(new BackpayRule.Line("Stone Age", Money.of(250)),
                        new BackpayRule.Line("The End?", Money.of(750)),
                        new BackpayRule.Line("Return to Sender", Money.of(1750)));
    }

    @Test
    @DisplayName("nothing made, or everything already paid, is nothing owed")
    void nothing() {
        assertThat(rule.owed(List.of(), Map.of(), BackpayRuleTest::price).total()).isEqualTo(Money.ZERO);
        assertThat(rule.owed(List.of(new BackpayRule.Made("Stone Age", Frame.TASK)), Map.of("Stone Age", Money.of(400)),
                BackpayRuleTest::price).count()).isZero();
    }
}
