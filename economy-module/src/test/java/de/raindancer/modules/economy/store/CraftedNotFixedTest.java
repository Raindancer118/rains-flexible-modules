package de.raindancer.modules.economy.store;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.RecipeShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CraftedNotFixedTest {

    private static PriceBook shippedWith(List<RecipeShape> recipes) throws Exception {
        byte[] raw;
        try (InputStream in = EconomySettings.class.getResourceAsStream(BasePrices.RESOURCE)) {
            assertThat(in).isNotNull();
            raw = in.readAllBytes();
        }
        PriceBook book = new PriceBook(currency -> BasePrices.parse(new ByteArrayInputStream(raw), currency),
                material -> 0.0, material -> 64);
        book.recompute(EconomySettings.DEFAULTS, recipes);
        return book;
    }

    @Test
    @DisplayName("a name tag is crafted from paper and an iron nugget now, so it costs about that, not a rare find's price")
    void nameTags() throws Exception {
        List<RecipeShape> recipes = List.of(
                new RecipeShape("PAPER", 3, List.of(List.of("SUGAR_CANE"), List.of("SUGAR_CANE"), List.of("SUGAR_CANE")),
                        RecipeShape.Process.CRAFT),
                new RecipeShape("IRON_INGOT", 1, List.of(List.of("RAW_IRON")), RecipeShape.Process.SMELT),
                new RecipeShape("IRON_NUGGET", 9, List.of(List.of("IRON_INGOT")), RecipeShape.Process.CRAFT),
                new RecipeShape("NAME_TAG", 1, List.of(List.of("PAPER"), List.of("IRON_NUGGET")), RecipeShape.Process.CRAFT));
        Money nameTag = shippedWith(recipes).tag("NAME_TAG").value();
        assertThat(nameTag.isPositive()).isTrue();
        assertThat(nameTag.isMoreThan(Money.of(50))).as("about a paper and a nugget, was 300: " + nameTag).isFalse();
    }
}
