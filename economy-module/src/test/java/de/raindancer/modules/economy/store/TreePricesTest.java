package de.raindancer.modules.economy.store;

import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.PriceTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TreePricesTest {

    /** Every kind of leaves the game has — read from the game, so a new tree's leaves cannot be missed. */
    static List<String> leaves() {
        return java.util.Arrays.stream(org.bukkit.Material.values())
                .filter(material -> !material.isLegacy() && material.name().endsWith("_LEAVES"))
                .map(Enum::name).toList();
    }

    @Test
    @DisplayName("every kind of leaves, and the poplar's log and sapling, can be bought and sold")
    void treesTrade() throws Exception {
        PriceBook book;
        try (InputStream in = EconomySettings.class.getResourceAsStream(BasePrices.RESOURCE)) {
            assertThat(in).isNotNull();
            byte[] raw = in.readAllBytes();
            book = new PriceBook(currency -> BasePrices.parse(new java.io.ByteArrayInputStream(raw), currency),
                    material -> 0.0, material -> 64);
        }
        book.recompute(EconomySettings.DEFAULTS, List.of());
        List<String> wanted = new java.util.ArrayList<>(leaves());
        wanted.addAll(List.of("POPLAR_LOG", "POPLAR_SAPLING"));
        assertThat(leaves()).as("the scan found the leaves").hasSizeGreaterThanOrEqualTo(14);
        org.assertj.core.api.SoftAssertions soft = new org.assertj.core.api.SoftAssertions();
        for (String tree : wanted) {
            PriceTag tag = book.tag(tree);
            soft.assertThat(tag.buyable()).as(tree + " buyable (buy " + tag.buy() + ")").isTrue();
            soft.assertThat(tag.sellable()).as(tree + " sellable (sell " + tag.sell() + ")").isTrue();
        }
        soft.assertAll();
    }
}
