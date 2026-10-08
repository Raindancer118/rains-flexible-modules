package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Account;
import de.raindancer.modules.economy.model.Spot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderboardRuleTest {

    private final LeaderboardRule rule = new LeaderboardRule();

    private static Account account(String name, long balance) {
        return new Account(UUID.randomUUID(), name, Money.of(balance), false, 0, -1, 0);
    }

    @Test
    @DisplayName("the top is the richest first, as many as asked, and nobody's place is invented")
    void top() {
        List<Account> ranking = List.of(account("Ada", 900), account("Bo", 500), account("Cy", 100));
        assertThat(rule.top(ranking, 2)).extracting(Account::name).containsExactly("Ada", "Bo");
        assertThat(rule.top(ranking, 10)).hasSize(3);
        assertThat(rule.top(ranking, 0)).isEmpty();
    }

    @Test
    @DisplayName("a place is written with its medal colour for the first three")
    void places() {
        assertThat(rule.colourOf(1)).isEqualTo("<gold>");
        assertThat(rule.colourOf(2)).isEqualTo("<gray>");
        assertThat(rule.colourOf(3)).isEqualTo("<#cd7f32>");
        assertThat(rule.colourOf(4)).isEqualTo("<white>");
    }

    @Test
    @DisplayName("a long name is shortened so a sidebar line fits")
    void names() {
        assertThat(rule.shortName("Raindancer118", 10)).isEqualTo("Raindance…");
        assertThat(rule.shortName("Bo", 10)).isEqualTo("Bo");
    }

    @Test
    @DisplayName("spots are read from the settings and written back the same way")
    void spots() {
        Spot spot = Spot.parse("world 10.5 70 -3.25").orElseThrow();
        assertThat(spot.world()).isEqualTo("world");
        assertThat(Spot.parse(spot.written())).contains(spot);
        assertThat(Spot.parse("world 1 2")).isEmpty();
        assertThat(Spot.parse("world a b c")).isEmpty();
        assertThat(spot.distanceSquared("nether", 10.5, 70, -3.25)).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("passive income is off until an owner switches it on; the sidebar is on")
    void defaults() {
        assertThat(EconomySettings.DEFAULTS.incomeEnabled()).isFalse();
        assertThat(EconomySettings.DEFAULTS.sidebarEnabled()).isTrue();
        assertThat(EconomySettings.DEFAULTS.sidebarRichest()).isEqualTo(3);
        assertThat(EconomySettings.DEFAULTS.leaderboardSize()).isEqualTo(10);
    }
}
