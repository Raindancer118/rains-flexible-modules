package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /shop search …} on a real server: one page of everything the shop has that answers to the words — items,
 * enchanted books, potions, Rain's enchantments — typos forgiven and levels typed as numbers, and a book bought from
 * the results.
 */
@Tag("e2e")
class ShopSearchScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Test
    @DisplayName("search finds items, books, potions and Rain's enchantments, misspelt or not, and sells from there")
    void search() {
        try (Server server = Server.start("shop-search",
                List.of("economy-standalone:RainsEconomy-.*", "enchants-standalone:RainsEnchants-.*"), List.of(),
                Map.of("plugins/RainsEconomy/config.yml", "rains-enchantments:\n  enabled: true\n"),
                List.of("The economy is up"))) {
            Bot ada = server.player("Ada");
            server.paper.console("eco give Ada 200000");

            ada.run("shop search diamond");
            assertThat(names(ada)).contains("Diamond", "Diamond Sword", "Diamond Block");
            assertThat(first(ada)).as("the item called exactly that comes first").isEqualTo("Diamond");
            ada.closeWindow();

            ada.run("shop search block of diamond");
            assertThat(names(ada)).as("the way Minecraft calls it").contains("Diamond Block");
            ada.closeWindow();

            ada.run("shop search diamnd swrod");
            assertThat(names(ada)).as("two typos").contains("Diamond Sword");
            ada.closeWindow();

            ada.run("shop search sharp 5");
            // A book's name is the game's translation — "Sharpness V" in a client, its keys to a bot without a language.
            String sharpnessFive = "enchantment.minecraft.sharpness enchantment.level.5";
            assertThat(names(ada)).as("an enchanted book, its level typed as a number").contains(sharpnessFive);
            int book = ada.window().flatMap(window -> window.slotNamed(sharpnessFive)).orElseThrow();
            ada.clickSlot(book);
            Await.until("the book was bought", WAIT, () -> server.paper.console("clear Ada minecraft:enchanted_book 0")
                    .contains("Found 1"));
            ada.closeWindow();

            ada.run("shop search healing");
            // A bottle's name is the game's translation, which a bot without a language sees as nothing: its kind says it.
            names(ada);
            assertThat(ada.window().orElseThrow().top().values()).anyMatch(item -> item.material().equalsIgnoreCase("potion"));
            ada.closeWindow();

            ada.run("shop search autopilot");
            assertThat(names(ada)).as("Rain's own enchantments are in the shop too").anyMatch(name -> name.startsWith("Autopilot"));
            ada.closeWindow();

            ada.run("shop search xyzzy");
            assertThat(names(ada)).contains("Nothing called that");

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEconomy", "RainsEnchants").stream()
                    .filter(line -> !line.contains("resource pack server could not start")).toList()).isEmpty();
        }
    }

    private static List<String> names(Bot bot) {
        Await.until("a results page is open", WAIT, () -> bot.window().filter(window -> !window.top().isEmpty()).isPresent());
        Await.ticks(10);
        return bot.window().map(window -> window.top().values().stream().map(Bot.Item::name).toList()).orElse(List.of());
    }

    private static String first(Bot bot) {
        Optional<Bot.Item> at = bot.window().map(window -> window.top().get(0));
        return at.map(Bot.Item::name).orElse("");
    }
}
