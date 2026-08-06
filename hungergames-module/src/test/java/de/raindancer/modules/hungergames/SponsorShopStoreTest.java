package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.store.SponsorShopStore;
import de.raindancer.modules.hungergames.store.SponsorShopStore.CustomItemReward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.EffectReward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.MaterialReward;
import de.raindancer.modules.hungergames.store.SponsorShopStore.ShopItem;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@link SponsorShopStore}: the shop's line syntax (ported from {@code ShopItemParserTest}), and the
 * file's own invariants.
 */
class SponsorShopStoreTest {

    private static final Set<String> CUSTOM_ITEMS = Set.of("FIENDFINDER");

    @Nested
    @DisplayName("the line syntax")
    class Syntax {

        @Test
        @DisplayName("a material reward")
        void parsesMaterialReward() {
            ShopItem item = SponsorShopStore.parse("bread_pack|BREAD:8|1|Food Pack", CUSTOM_ITEMS);

            assertThat(item.id()).isEqualTo("bread_pack");
            assertThat(item.cost()).isEqualTo(1);
            assertThat(item.displayName()).isEqualTo("Food Pack");
            MaterialReward reward = (MaterialReward) item.reward();
            assertThat(reward.material()).isEqualTo(Material.BREAD);
            assertThat(reward.amount()).isEqualTo(8);
        }

        @Test
        @DisplayName("an effect reward")
        void parsesEffectReward() {
            ShopItem item = SponsorShopStore.parse("speed_15s|EFFECT:SPEED:15:0|3|Speed 15 Seconds", CUSTOM_ITEMS);

            EffectReward reward = (EffectReward) item.reward();
            assertThat(reward.effectName()).isEqualTo("SPEED");
            assertThat(reward.durationSeconds()).isEqualTo(15);
            assertThat(reward.amplifier()).isEqualTo(0);
        }

        @Test
        @DisplayName("a custom-item reward, checked against the known ids handed in")
        void parsesCustomItemReward() {
            ShopItem item = SponsorShopStore.parse("ff|ITEM:FIENDFINDER:1|4|Fiendfinder", CUSTOM_ITEMS);

            CustomItemReward reward = (CustomItemReward) item.reward();
            assertThat(reward.customId()).isEqualTo("FIENDFINDER");
            assertThat(reward.amount()).isEqualTo(1);

            assertThat(catchThrowable(() -> SponsorShopStore.parse("x|ITEM:UNKNOWN:1|1|X", CUSTOM_ITEMS)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("invalid entries are rejected")
        void rejectsInvalidEntries() {
            assertThat(catchThrowable(() -> SponsorShopStore.parse("too|few|fields", CUSTOM_ITEMS)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowable(() -> SponsorShopStore.parse("x|NOT_A_MATERIAL:1|1|X", CUSTOM_ITEMS)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowable(() -> SponsorShopStore.parse("x|BREAD:0|1|X", CUSTOM_ITEMS)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowable(() -> SponsorShopStore.parse("x|BREAD:1|0|X", CUSTOM_ITEMS)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowable(() -> SponsorShopStore.parse("x|BREAD:1|one|X", CUSTOM_ITEMS)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowable(() -> SponsorShopStore.parse("x|EFFECT:SPEED:15|1|X", CUSTOM_ITEMS)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(catchThrowable(() -> SponsorShopStore.parse("|BREAD:1|1|X", CUSTOM_ITEMS)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a leading '#' disables an entry without losing it")
        void disabledEntryKeepsItsData() {
            ShopItem item = SponsorShopStore.parse("#x|BREAD:1|1|Bread", CUSTOM_ITEMS);

            assertThat(item.enabled()).isFalse();
            assertThat(SponsorShopStore.serialize(item)).startsWith("#");
        }

        @Test
        @DisplayName("validateList reports duplicates and syntax errors")
        void validateListReportsProblems() {
            assertThat(SponsorShopStore.validateList(
                    List.of("a|BREAD:1|1|Bread", "b|ARROW:16|1|Arrows"), CUSTOM_ITEMS)).isEmpty();
            assertThat(SponsorShopStore.validateList(
                    List.of("a|BREAD:1|1|Bread", "a|ARROW:16|1|Arrows"), CUSTOM_ITEMS)).isPresent();
            assertThat(SponsorShopStore.validateList(List.of("broken"), CUSTOM_ITEMS)).isPresent();
        }
    }

    @Nested
    @DisplayName("the file")
    class TheFile {

        @Test
        @DisplayName("a full shop survives a save and a load")
        void roundTrip(@TempDir Path dir) {
            SponsorShopStore store = new SponsorShopStore(dir.resolve("sponsor-shop.yml"));
            List<ShopItem> items = List.of(
                    SponsorShopStore.parse("bread_pack|BREAD:8|1|Food Pack", CUSTOM_ITEMS),
                    SponsorShopStore.parse("ff|ITEM:FIENDFINDER:1|4|Fiendfinder", CUSTOM_ITEMS));

            assertThat(store.save(items)).isTrue();

            assertThat(store.load(CUSTOM_ITEMS)).isEqualTo(items);
            assertThat(store.problems()).isEmpty();
        }

        @Test
        @DisplayName("no file yet is an empty shop, not an exception")
        void missingFile(@TempDir Path dir) {
            SponsorShopStore store = new SponsorShopStore(dir.resolve("sponsor-shop.yml"));

            assertThat(store.load(CUSTOM_ITEMS)).isEmpty();
            assertThat(store.problems()).isEmpty();
        }

        @Test
        @DisplayName("a corrupt file is reported, quarantined, and never overwritten in place")
        void corruptYaml(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("sponsor-shop.yml");
            Files.writeString(file, "items: [broken: [[[");
            SponsorShopStore store = new SponsorShopStore(file);

            assertThat(store.load(CUSTOM_ITEMS)).isEmpty();
            assertThat(store.problems()).isNotEmpty();
            assertThat(Files.exists(file)).isFalse();
        }

        @Test
        @DisplayName("one bad line rejects the whole shop rather than silently offering a shorter one")
        void oneBadLineRejectsTheWholeFile(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("sponsor-shop.yml");
            Files.writeString(file, """
                    items:
                      - "bread_pack|BREAD:8|1|Food Pack"
                      - "this is not a shop entry"
                    """);
            SponsorShopStore store = new SponsorShopStore(file);

            assertThat(store.load(CUSTOM_ITEMS)).isEmpty();
            assertThat(store.problems()).isNotEmpty();
            assertThat(Files.exists(file)).isTrue();
        }

        @Test
        @DisplayName("a duplicate id also rejects the whole shop")
        void duplicateIdRejectsTheWholeFile(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("sponsor-shop.yml");
            Files.writeString(file, """
                    items:
                      - "a|BREAD:1|1|Bread"
                      - "a|ARROW:16|1|Arrows"
                    """);
            SponsorShopStore store = new SponsorShopStore(file);

            assertThat(store.load(CUSTOM_ITEMS)).isEmpty();
            assertThat(store.problems()).isNotEmpty();
        }
    }
}
