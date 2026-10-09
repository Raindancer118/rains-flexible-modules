package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.PriceChange;
import de.raindancer.core.social.economy.PriceModifier;
import de.raindancer.core.social.economy.PriceModifiers;
import de.raindancer.core.social.economy.TradeSide;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.YourPrice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PersonalPriceRuleTest {

    private final PersonalPriceRule rule = new PersonalPriceRule();
    private final UUID cook = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private static PriceTag tag(long buy, long sell) {
        return new PriceTag("BREAD", Money.of(buy), Money.of(buy), Money.of(sell), true, true, PriceTag.Source.RECIPE);
    }

    private void cookGets(int buyPercent, int sellPercent) {
        PriceModifier role = (player, material, side) -> player.equals(cook)
                ? Optional.of(new PriceChange(side == TradeSide.BUY ? buyPercent : sellPercent, "Cook"))
                : Optional.empty();
        PriceModifiers.provide(mock(org.bukkit.plugin.Plugin.class), role);
    }

    @AfterEach
    void reset() {
        PriceModifiers.clear();
    }

    @Test
    @DisplayName("somebody without a role pays and is paid the shop's price")
    void everybody() {
        cookGets(-25, 10);
        YourPrice price = rule.forPlayer(stranger, tag(1000, 500));
        assertThat(price.buy()).isEqualTo(Money.of(1000));
        assertThat(price.sell()).isEqualTo(Money.of(500));
        assertThat(price.buyChanged()).isFalse();
        assertThat(price.sellChanged()).isFalse();
    }

    @Test
    @DisplayName("a role's discount and bonus show in their price, with the reason")
    void discounted() {
        cookGets(-25, 10);
        YourPrice price = rule.forPlayer(cook, tag(1000, 500));
        assertThat(price.buy()).isEqualTo(Money.of(750));
        assertThat(price.sell()).isEqualTo(Money.of(550));
        assertThat(price.buyChanged()).isTrue();
        assertThat(price.buyChange().reasons()).containsExactly("Cook");
    }

    @Test
    @DisplayName("a cheaper buy price pulls the sell price under it — buying and selling back never earns")
    void noMoneyMachine() {
        cookGets(-25, 0);
        YourPrice price = rule.forPlayer(cook, tag(1000, 900));
        assertThat(price.buy()).isEqualTo(Money.of(750));
        assertThat(price.sell()).isEqualTo(Money.of(749));
        assertThat(price.sellChanged()).isTrue();
    }

    @Test
    @DisplayName("a sell bonus is capped a cent below the player's own buy price")
    void bonusCapped() {
        cookGets(0, 50);
        YourPrice price = rule.forPlayer(cook, tag(1000, 900));
        assertThat(price.sell()).isEqualTo(Money.of(999));
    }

    @Test
    @DisplayName("an item the shop does not sell keeps its sell bonus uncapped by a price nobody can pay")
    void notSold() {
        cookGets(-25, 10);
        PriceTag onlyBought = new PriceTag("WHEAT", Money.of(1000), Money.ZERO, Money.of(500), false, true,
                PriceTag.Source.RECIPE);
        assertThat(rule.forPlayer(cook, onlyBought).sell()).isEqualTo(Money.of(550));
    }

    @Test
    @DisplayName("nothing is priced for nobody")
    void nobody() {
        cookGets(-25, 10);
        YourPrice price = rule.forPlayer(null, tag(1000, 500));
        assertThat(price.buy()).isEqualTo(Money.of(1000));
    }

    @Test
    @DisplayName("a discount is taken off the whole line, so a cheap item still gets it")
    void line() {
        cookGets(-25, 10);
        YourPrice seeds = rule.forPlayer(cook, new PriceTag("WHEAT_SEEDS", Money.of(2), Money.of(2), Money.of(1),
                true, true, PriceTag.Source.BASE));
        assertThat(seeds.buy()).as("one alone rounds up").isEqualTo(Money.of(2));
        assertThat(seeds.buyFor(64)).contains(Money.of(96));
        assertThat(seeds.sellFor(64)).as("64 × 1 + 10% = 70.4").contains(Money.of(70));
        assertThat(seeds.buyFor(0)).contains(Money.ZERO);
    }

    @Test
    @DisplayName("a line's sell is capped under the same line's buy")
    void lineCapped() {
        cookGets(-25, 50);
        YourPrice price = rule.forPlayer(cook, tag(10, 9));
        // buy 10 × 10 × 0.75 = 75; sell 10 × 9 × 1.5 = 135 → 74
        assertThat(price.sellFor(10)).contains(Money.of(74));
    }

    @Test
    @DisplayName("a line too big to count is no price rather than a wrong one")
    void overflow() {
        YourPrice price = rule.forPlayer(cook, tag(Long.MAX_VALUE / 2, 1));
        assertThat(price.buyFor(3)).isEmpty();
    }
}
