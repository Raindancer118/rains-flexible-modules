package de.raindancer.modules.economy.screen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrendTest {

    @Test
    @DisplayName("a moved price says which way and how far; a price that has hardly moved says nothing")
    void trend() {
        assertThat(ShopItemsMenu.trend(1.0)).isEmpty();
        assertThat(ShopItemsMenu.trend(1.004)).isEmpty();
        assertThat(ShopItemsMenu.trend(1.23)).contains("▲ 23%").contains("bought a lot");
        assertThat(ShopItemsMenu.trend(0.82)).contains("▼ 18%").contains("sold a lot");
    }
}
