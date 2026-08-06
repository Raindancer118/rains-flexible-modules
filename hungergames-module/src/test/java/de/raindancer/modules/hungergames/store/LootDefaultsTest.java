package de.raindancer.modules.hungergames.store;

import de.raindancer.core.content.loot.LootEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LootDefaults}: the six pools the live tournament server was tuned on, checked against the numbers
 * in that server's own {@code loot.yml} rather than against anything this port invented.
 */
@DisplayName("LootDefaults ships the six tables the live server actually ran, unchanged")
class LootDefaultsTest {

    private static final Map<String, LootDefaults.Table> ALL = LootDefaults.all();

    @Test
    @DisplayName("there are exactly the six tables the live file named, no more and no fewer")
    void exactlyTheExpectedSixNames() {
        assertThat(ALL.keySet()).containsExactlyInAnyOrder(
                "chest", "copper-chest", "trapped-chest", "barrel", "shelf", "supply-drop");
    }

    @Test
    @DisplayName("every table has at least one entry — none of them shipped empty")
    void noTableIsEmpty() {
        ALL.forEach((name, table) -> assertThat(table.entries())
                .as("table '%s'", name)
                .isNotEmpty());
    }

    @Test
    @DisplayName("none of the six tables is a single item pretending to be a pool")
    void noTableIsOnlyOneItem() {
        ALL.forEach((name, table) -> assertThat(table.entries().size())
                .as("table '%s'", name)
                .isGreaterThan(1));
    }

    @Test
    @DisplayName("every entry rolls with a positive weight — a zero-weight entry can never be picked")
    void everyWeightIsPositive() {
        ALL.forEach((name, table) -> table.entries().forEach(entry ->
                assertThat(entry.weight())
                        .as("an entry in table '%s'", name)
                        .isPositive()));
    }

    @Test
    @DisplayName("the fill percentages match the live server's loot.yml exactly")
    void fillPercentagesMatchTheLiveFile() {
        assertThat(ALL.get("chest").fillPercent()).isEqualTo(40);
        assertThat(ALL.get("copper-chest").fillPercent()).isEqualTo(45);
        assertThat(ALL.get("trapped-chest").fillPercent()).isEqualTo(55);
        assertThat(ALL.get("barrel").fillPercent()).isEqualTo(35);
        assertThat(ALL.get("shelf").fillPercent()).isEqualTo(80);
        assertThat(ALL.get("supply-drop").fillPercent()).isEqualTo(45);
    }

    @Test
    @DisplayName("no material named in these tables is missing from this server's build")
    void problemsAreEmptyOnThisServer() {
        assertThat(LootDefaults.problems()).isEmpty();
    }

    @Test
    @DisplayName("every table is tier 1 or higher — a table is never worse than the ordinary chest")
    void everyTierIsAtLeastOne() {
        ALL.values().forEach(table -> assertThat(table.tier()).isGreaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("the cornucopia and the shelf are the richest tables, strictly above the plain chest")
    void trappedChestAndShelfOutrankTheChest() {
        int chestTier = ALL.get("chest").tier();
        assertThat(ALL.get("trapped-chest").tier()).isGreaterThan(chestTier);
        assertThat(ALL.get("shelf").tier()).isGreaterThan(chestTier);
    }

    @Nested
    @DisplayName("the entry counts, checked against the live file's per-table item lists")
    class EntryCounts {

        @Test
        @DisplayName("chest has all seventeen of the live file's entries")
        void chest() {
            assertThat(ALL.get("chest").entries()).hasSize(17);
        }

        @Test
        @DisplayName("copper-chest has all twenty of the live file's entries, including its two custom items")
        void copperChest() {
            assertThat(ALL.get("copper-chest").entries()).hasSize(20);
            assertThat(customKeys(ALL.get("copper-chest")))
                    .containsExactlyInAnyOrder("hungergames:krueckauwasser", "hungergames:smoke-bomb");
        }

        @Test
        @DisplayName("trapped-chest has all twenty-four of the live file's entries, including its three custom items")
        void trappedChest() {
            assertThat(ALL.get("trapped-chest").entries()).hasSize(24);
            assertThat(customKeys(ALL.get("trapped-chest")))
                    .containsExactlyInAnyOrder(
                            "hungergames:fiendfinder", "hungergames:stupidness-protector", "hungergames:leap");
        }

        @Test
        @DisplayName("barrel has all twelve of the live file's entries, including its one custom item")
        void barrel() {
            assertThat(ALL.get("barrel").entries()).hasSize(12);
            assertThat(customKeys(ALL.get("barrel"))).containsExactly("hungergames:repulse");
        }

        @Test
        @DisplayName("shelf has all nine of the live file's entries")
        void shelf() {
            assertThat(ALL.get("shelf").entries()).hasSize(9);
        }

        @Test
        @DisplayName("supply-drop has all eleven of the live file's supply_drop entries")
        void supplyDrop() {
            assertThat(ALL.get("supply-drop").entries()).hasSize(11);
            assertThat(customKeys(ALL.get("supply-drop"))).containsExactly("hungergames:fiendfinder");
        }

        private java.util.List<String> customKeys(LootDefaults.Table table) {
            return table.entries().stream()
                    .filter(LootEntry::isCustom)
                    .map(LootEntry::customKey)
                    .toList();
        }
    }
}
