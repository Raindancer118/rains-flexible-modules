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

    @Nested
    @DisplayName("how an item is named")
    class ItemNaming {

        /**
         * The bug this was written for, and it took a live config to find.
         *
         * <p>A shop line says {@code ITEM:SMOKE_BOMB:1} — screaming snake case, because that is how the old
         * plugin wrote every item name and therefore what is in every existing config.yml. The registered id
         * is {@code smoke-bomb}, hyphenated, because that is how Core spells a custom item's id. Comparing
         * them after nothing but an {@code toUpperCase} gives {@code SMOKE_BOMB} against {@code SMOKE-BOMB},
         * which does not match.
         *
         * <p>And a line that does not parse rejects <em>the whole shop file</em> — deliberately, so nobody is
         * offered half a shop. So eight of the live server's twelve entries failing meant the other four
         * failed too: no sponsor shop at all, for a feature tributes earn tokens towards all evening.
         *
         * <p>{@code FIENDFINDER} matched, which is what made this so easy to miss: the one item with no
         * separator in its name worked, so a spot check of the parser looked fine.
         */
        @Test
        @DisplayName("an underscore and a hyphen name the same item")
        void separatorsAreEquivalent() {
            Set<String> registered = Set.of("smoke-bomb", "aura-of-protection");

            // Exactly what a live config.yml contains.
            assertThat(SponsorShopStore.parse("sb|ITEM:SMOKE_BOMB:1|6|Smoke bomb", registered).reward())
                    .isEqualTo(new SponsorShopStore.CustomItemReward("smoke-bomb", 1));
            assertThat(SponsorShopStore.parse("a|ITEM:AURA_OF_PROTECTION:1|8|Aura", registered).reward())
                    .isEqualTo(new SponsorShopStore.CustomItemReward("aura-of-protection", 1));
        }

        @Test
        @DisplayName("the id is stored the way the registries spell it, whatever the file said")
        void theCanonicalFormIsKept() {
            Set<String> registered = Set.of("smoke-bomb");

            // Otherwise the reward carries a name nothing can look up, and the purchase succeeds while the
            // player receives nothing — which is worse than a refusal, because there is nothing to report.
            for (String written : List.of("SMOKE_BOMB", "smoke_bomb", "Smoke-Bomb", "smoke-bomb")) {
                assertThat(SponsorShopStore.parse("x|ITEM:" + written + ":1|1|X", registered).reward())
                        .as("written as '%s'", written)
                        .isEqualTo(new SponsorShopStore.CustomItemReward("smoke-bomb", 1));
            }
        }

        @Test
        @DisplayName("an item that really does not exist is still refused")
        void anUnknownItemIsStillUnknown() {
            assertThat(catchThrowable(() ->
                    SponsorShopStore.parse("x|ITEM:NO_SUCH_THING:1|1|X", Set.of("smoke-bomb"))))
                    .as("being lenient about separators must not become being lenient about everything")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the live server's whole shop line loads")
        void theRealShopLoads() {
            // The twelve entries off the Fachschaft server, verbatim. Nine of them are custom items.
            Set<String> registered = Set.of("fiendfinder", "smoke-bomb", "lightning-strike",
                    "hermes-boots", "krueckauwasser", "stupidness-protector", "leap", "medikit",
                    "aura-of-protection");
            List<String> live = List.of(
                    "bread_pack|BREAD:8|1|Essen Paket",
                    "arrows|ARROW:16|1|Pfeile",
                    "iron_ingot|IRON_INGOT:2|2|Eisen",
                    "fiendfinder|ITEM:FIENDFINDER:1|4|Fiendfinder",
                    "smoke_bomb|ITEM:SMOKE_BOMB:1|6|Rauchbombe",
                    "lightning_strike|ITEM:LIGHTNING_STRIKE:1|12|Blitzschlag",
                    "hermes_boots|ITEM:HERMES_BOOTS:2|9|Hermes' Stiefel",
                    "krueckauwasser|ITEM:KRUECKAUWASSER:1|7|Krückauwasser",
                    "stupidness_protector|ITEM:STUPIDNESS_PROTECTOR:1|5|Trottel-Schutz",
                    "leap|ITEM:LEAP:1|4|Sprungfeder",
                    "medikit|ITEM:MEDIKIT:1|12|Medikit",
                    "aura_of_protection|ITEM:AURA_OF_PROTECTION:1|8|Aura der Bewahrung");

            assertThat(SponsorShopStore.validateList(live, registered))
                    .as("one unparseable line rejects the whole file, so this is all twelve or none")
                    .isEmpty();
        }
    }
}
