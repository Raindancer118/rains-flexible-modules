package de.raindancer.modules.farmworld;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.farmworld.rules.FarmEntryRule;
import de.raindancer.modules.farmworld.rules.FarmEntryRule.Choice;
import de.raindancer.modules.farmworld.rules.FarmEntryRule.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FarmEntryRuleTest {

    private final FarmEntryRule rule = new FarmEntryRule();
    private final Money entry = Money.of(500);
    private final Money pass = Money.of(2000);
    private static final Money NONE = Money.ZERO;

    @Test
    @DisplayName("with both prices off, entering is free")
    void freeByDefault() {
        assertThat(rule.decide(NONE, NONE, false, false, Choice.NONE)).isEqualTo(Entry.FREE);
    }

    @Test
    @DisplayName("an active pass makes every entry free, whatever the prices")
    void passActive() {
        assertThat(rule.decide(entry, pass, true, false, Choice.NONE)).isEqualTo(Entry.FREE);
        assertThat(rule.decide(entry, NONE, true, false, Choice.NONE)).isEqualTo(Entry.FREE);
    }

    @Test
    @DisplayName("staff who bypass fees pay nothing")
    void bypass() {
        assertThat(rule.decide(entry, pass, false, true, Choice.NONE)).isEqualTo(Entry.FREE);
    }

    @Test
    @DisplayName("only an entry price: pay it, no question asked")
    void onlyEntry() {
        assertThat(rule.decide(entry, NONE, false, false, Choice.NONE)).isEqualTo(Entry.PAY_ENTRY);
    }

    @Test
    @DisplayName("only a pass price: the pass is the way in")
    void onlyPass() {
        assertThat(rule.decide(NONE, pass, false, false, Choice.NONE)).isEqualTo(Entry.BUY_PASS);
    }

    @Test
    @DisplayName("both prices: ask which, until they have said")
    void both() {
        assertThat(rule.decide(entry, pass, false, false, Choice.NONE)).isEqualTo(Entry.CHOOSE);
        assertThat(rule.decide(entry, pass, false, false, Choice.ENTRY)).isEqualTo(Entry.PAY_ENTRY);
        assertThat(rule.decide(entry, pass, false, false, Choice.PASS)).isEqualTo(Entry.BUY_PASS);
    }

    @Test
    @DisplayName("a choice made for a price that no longer exists is ignored")
    void staleChoice() {
        assertThat(rule.decide(NONE, pass, false, false, Choice.ENTRY)).isEqualTo(Entry.BUY_PASS);
        assertThat(rule.decide(entry, NONE, false, false, Choice.PASS)).isEqualTo(Entry.PAY_ENTRY);
    }
}
