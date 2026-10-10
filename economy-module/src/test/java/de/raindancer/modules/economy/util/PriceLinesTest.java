package de.raindancer.modules.economy.util;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.SellBreakdown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PriceLinesTest {

    @Test
    @DisplayName("nothing changing the sell price says nothing")
    void quiet() {
        assertThat(PriceLines.sellNotes(Currency.DEFAULT, SellBreakdown.NONE)).isEmpty();
    }

    @Test
    @DisplayName("under the sell price, a line for each thing that changed it, with its percent")
    void eachChange() {
        List<String> lines = PriceLines.sellNotes(Currency.DEFAULT, new SellBreakdown(10, List.of("Cook"), -5, -20,
                3, 30, true, Optional.of(Money.of(1200))));
        assertThat(lines).hasSize(5);
        assertThat(lines.get(0)).contains("Cook").contains("+10%");
        assertThat(lines.get(1)).contains("-5%").contains("Economy");
        assertThat(lines.get(2)).contains("-20%").contains("3 stacks").contains("30 min");
        assertThat(lines.get(3)).contains("under");
        assertThat(lines.get(4)).contains("today");
    }
}
