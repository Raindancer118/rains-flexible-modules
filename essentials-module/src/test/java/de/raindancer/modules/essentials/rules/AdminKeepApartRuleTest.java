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

    @Test
    @DisplayName("with containers allowed, every window that keeps items opens — trading with villagers still does not")
    void containersAllowed() {
        for (String type : new String[]{"CHEST", "BARREL", "SHULKER_BOX", "HOPPER", "DISPENSER", "DROPPER", "FURNACE",
                "BLAST_FURNACE", "SMOKER", "BREWING", "BEACON", "CRAFTER", "LECTERN", "CHISELED_BOOKSHELF"}) {
            assertThat(rule.mayUseWindow(type, true)).as(type).isTrue();
            assertThat(rule.mayUseWindow(type, false)).as(type + " when kept apart").isFalse();
        }
        assertThat(rule.mayUseWindow("MERCHANT", true)).isFalse();
    }

    @Test
    @DisplayName("what went in and what came out of a container, by item, for the audit log")
    void changes() {
        var before = java.util.Map.of("DIAMOND", 10, "IRON_INGOT", 5, "DIRT", 64);
        var after = java.util.Map.of("DIAMOND", 74, "DIRT", 64, "NETHER_STAR", 1);
        AdminKeepApartRule.Changes changes = rule.changes(before, after);
        assertThat(changes.in()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("DIAMOND", 64, "NETHER_STAR", 1));
        assertThat(changes.out()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("IRON_INGOT", 5));
        assertThat(changes.says(changes.in())).isEqualTo("64 Diamond, 1 Nether Star");
        assertThat(rule.changes(before, before).none()).isTrue();
    }
}
