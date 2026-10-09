package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Pack;
import de.raindancer.modules.economy.model.PackItem;
import de.raindancer.modules.economy.model.PackPrice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PackPriceRuleTest {

    private final PackPriceRule rule = new PackPriceRule();

    private static Pack pack(Money fixed, PackItem... contents) {
        return new Pack("explorer", "Explorer's Pack", "COMPASS", List.of(), List.of(contents), fixed, false);
    }

    private static Optional<Money> from(Map<String, Long> prices, PackItem line) {
        return Optional.ofNullable(prices.get(line.material())).map(unit -> Money.of(unit * line.amount()));
    }

    @Test
    @DisplayName("a pack costs its contents at the buyer's prices, less the pack discount, rounded up")
    void summed() {
        Map<String, Long> prices = Map.of("TORCH", 33L, "BREAD", 100L);
        Optional<PackPrice> price = rule.price(pack(null, new PackItem("TORCH", 10), new PackItem("BREAD", 3)),
                line -> from(prices, line), 10);
        // 330 + 300 = 630; 90% = 567
        assertThat(price).isPresent();
        assertThat(price.get().contents()).isEqualTo(Money.of(630));
        assertThat(price.get().price()).isEqualTo(Money.of(567));
        assertThat(rule.price(pack(null, new PackItem("TORCH", 1)), line -> from(prices, line), 10)
                .orElseThrow().price()).as("29.7 rounds up").isEqualTo(Money.of(30));
    }

    @Test
    @DisplayName("a pack holding something the shop does not sell is not for sale — never cheaper by leaving it out")
    void missingPrice() {
        Optional<PackPrice> price = rule.price(pack(null, new PackItem("TORCH", 10), new PackItem("ELYTRA", 1)),
                line -> line.material().equals("TORCH") ? Optional.of(Money.of(10L * line.amount())) : Optional.empty(), 10);
        assertThat(price).isEmpty();
    }

    @Test
    @DisplayName("an owner's own price is exactly that, whatever the contents cost")
    void fixed() {
        Optional<PackPrice> price = rule.price(pack(Money.of(5000), new PackItem("ELYTRA", 1)),
                line -> Optional.empty(), 10);
        assertThat(price.orElseThrow().price()).isEqualTo(Money.of(5000));
    }

    @Test
    @DisplayName("an empty pack is not for sale, and the discount is kept within 0–90%")
    void edges() {
        assertThat(rule.price(pack(null), line -> Optional.of(Money.of(line.amount())), 10)).isEmpty();
        assertThat(rule.price(pack(null, new PackItem("TORCH", 10)), line -> Optional.of(Money.of(100L * line.amount())), 500)
                .orElseThrow().price()).isEqualTo(Money.of(100));
        assertThat(rule.price(pack(null, new PackItem("TORCH", 10)), line -> Optional.of(Money.of(100L * line.amount())), -5)
                .orElseThrow().price()).isEqualTo(Money.of(1000));
    }

    @Test
    @DisplayName("contents survive being written onto the item and read back; garbage reads as nothing")
    void codec() {
        List<PackItem> contents = List.of(new PackItem("TORCH", 32), new PackItem("WHITE_BED", 1));
        assertThat(PackItem.decode(PackItem.encode(contents))).isEqualTo(contents);
        assertThat(PackItem.decode("TORCH*x;;*3;BREAD*0;STONE*5")).containsExactly(new PackItem("STONE", 5));
        assertThat(PackItem.decode(null)).isEmpty();
    }
}
