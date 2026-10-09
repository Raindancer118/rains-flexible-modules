package de.raindancer.modules.essentials.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminKeepApartRuleTest {

    private final AdminKeepApartRule rule = new AdminKeepApartRule();

    @Test
    @DisplayName("windows that hand everything back on close, and the admin's own, are fine")
    void ownAndWorkbenches() {
        for (String type : new String[]{"CRAFTING", "CREATIVE", "PLAYER", "WORKBENCH", "ENDER_CHEST", "ANVIL",
                "ENCHANTING", "GRINDSTONE", "SMITHING", "STONECUTTER", "LOOM", "CARTOGRAPHY"}) {
            assertThat(rule.mayUseWindow(type)).as(type).isTrue();
        }
    }

    @Test
    @DisplayName("anything that keeps items, or trades them away, is refused")
    void storage() {
        for (String type : new String[]{"CHEST", "BARREL", "SHULKER_BOX", "HOPPER", "DISPENSER", "DROPPER",
                "FURNACE", "BREWING", "MERCHANT", "BEACON", "CRAFTER", "LECTERN", "SOMETHING_NEW"}) {
            assertThat(rule.mayUseWindow(type)).as(type).isFalse();
        }
    }

    @Test
    @DisplayName("blocks that take an item from the hand are refused, others are not")
    void blocks() {
        for (String block : new String[]{"JUKEBOX", "LECTERN", "DECORATED_POT", "CHISELED_BOOKSHELF", "OAK_SHELF",
                "CAMPFIRE", "SOUL_CAMPFIRE", "FLOWER_POT", "COMPOSTER", "VAULT"}) {
            assertThat(rule.mayUseBlock(block)).as(block).isFalse();
        }
        for (String block : new String[]{"STONE", "OAK_DOOR", "LEVER", "CRAFTING_TABLE"}) {
            assertThat(rule.mayUseBlock(block)).as(block).isTrue();
        }
    }
}
