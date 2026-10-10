package de.raindancer.modules.economy.store;

import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.PriceTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MobDropPricesTest {

    /** What mobs drop when killed — players farm these, so the shop has to buy and sell every one. */
    // Drops that are themselves crafted or smelted (arrows, iron ingots, sugar…) are priced from their recipes on
    // a running server and are left out here, where no recipes are read.
    static final List<String> DROPS = List.of("ROTTEN_FLESH", "BONE", "STRING", "SPIDER_EYE",
            "GUNPOWDER", "ENDER_PEARL", "BLAZE_ROD", "BREEZE_ROD", "GHAST_TEAR", "SLIME_BALL",
            "LEATHER", "FEATHER", "INK_SAC", "GLOW_INK_SAC", "PHANTOM_MEMBRANE", "RABBIT_HIDE", "RABBIT_FOOT",
            "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS", "SHULKER_SHELL", "GLOWSTONE_DUST", "REDSTONE", "COAL",
            "EMERALD", "TURTLE_SCUTE", "ARMADILLO_SCUTE", "NAUTILUS_SHELL", "WITHER_SKELETON_SKULL",
            "SKELETON_SKULL", "ZOMBIE_HEAD", "CREEPER_HEAD", "NETHER_STAR", "BEEF", "PORKCHOP", "CHICKEN",
            "MUTTON", "RABBIT", "COD", "SALMON", "WHITE_WOOL", "EGG", "TOTEM_OF_UNDYING", "TRIDENT", "HONEYCOMB");

    @Test
    @DisplayName("every mob drop can be bought from and sold to the shop at the shipped prices")
    void mobDropsTrade() throws Exception {
        PriceBook book;
        try (InputStream in = EconomySettings.class.getResourceAsStream(BasePrices.RESOURCE)) {
            assertThat(in).isNotNull();
            byte[] raw = in.readAllBytes();
            book = new PriceBook(currency -> BasePrices.parse(new java.io.ByteArrayInputStream(raw), currency),
                    material -> 0.0, material -> 64);
        }
        book.recompute(EconomySettings.DEFAULTS, List.of());
        org.assertj.core.api.SoftAssertions soft = new org.assertj.core.api.SoftAssertions();
        for (String drop : DROPS) {
            PriceTag tag = book.tag(drop);
            soft.assertThat(tag.buyable()).as(drop + " buyable (buy " + tag.buy() + ")").isTrue();
            soft.assertThat(tag.sellable()).as(drop + " sellable (sell " + tag.sell() + ")").isTrue();
        }
        soft.assertAll();
    }
}
