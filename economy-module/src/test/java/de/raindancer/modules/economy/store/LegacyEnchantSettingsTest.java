package de.raindancer.modules.economy.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyEnchantSettingsTest {

    @Test
    @DisplayName("the old fixed prices per level are named with what they were set to, and left in the file")
    void named() {
        Map<String, Object> file = new HashMap<>();
        file.put("shop.enchant-price", "8k");
        file.put("shop.enchant-value", "40");
        String notice = LegacyEnchantSettings.notice(file::get, "Mending 12,526").orElseThrow();
        assertThat(notice).contains("shop.enchant-price (8k)").contains("shop.enchant-value (40)")
                .contains("no longer read").contains("left in config.yml").contains("Mending 12,526")
                .contains("enchanted_book:mending:1");
    }

    @Test
    @DisplayName("one of them is enough to be told; a config without them says nothing")
    void quiet() {
        assertThat(LegacyEnchantSettings.notice(key -> key.equals("shop.enchant-value") ? 40 : null, "")).isPresent();
        assertThat(LegacyEnchantSettings.notice(key -> null, "")).isEmpty();
    }
}
