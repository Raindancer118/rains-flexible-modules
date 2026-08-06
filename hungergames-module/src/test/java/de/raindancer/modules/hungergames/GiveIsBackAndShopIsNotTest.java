package de.raindancer.modules.hungergames;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.hungergames.command.HungerGamesCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /hg give} exists again, and {@code /hg shop} does not.
 *
 * <h2>Two opposite mistakes, found the same way</h2>
 * A line-by-line reading of the old plugin turned up a command the port had dropped and a command the port
 * had invented. Both are the kind of thing only a comparison finds, because neither produces an error: the
 * missing one is a command nobody can type, and the invented one works perfectly.
 *
 * <h3>The one that was dropped</h3>
 * {@code /hg give <item> [amount] [player]} ({@code HgCommand:105}, completing from
 * {@code CustomItems.ids()} at {@code :866}). It is how an item is tested. Fifteen custom items, most of them
 * reachable in a round only through a sponsor purchase or a lucky chest — so without this, checking whether
 * the grappling hook still pulls means playing until one drops.
 *
 * <h3>The one that was invented</h3>
 * {@code /hg shop}. The sponsor shop opens at a beacon, and that is the entire point of a beacon: something
 * worth crossing the arena for, at a place everybody else can see you standing. A command that opens the same
 * page from anywhere turns it into decoration. Not a port's decision to make.
 */
class GiveIsBackAndShopIsNotTest {

    private static final Path WORDING =
            Path.of("src/main/resources/de/raindancer/modules/hungergames/messages.yml");
    private static final Path COMMAND =
            Path.of("src/main/java/de/raindancer/modules/hungergames/command/HungerGamesCommand.java");

    @Nested
    @DisplayName("/hg give")
    class Give {

        @Test
        @DisplayName("it is a branch of /hg, not a name in a help line")
        void itIsDispatched() {
            // A subcommand that only appears in the usage list and the help text is one a gamemaster types
            // and gets "unknown subcommand" for. The dispatch is the thing that has to exist.
            assertThat(source()).contains("case \"give\" -> give(");
        }

        @Test
        @DisplayName("it is offered where /hg is described, so it is discoverable")
        void itIsAdvertised() {
            assertThat(hgCommand().options())
                    .as("a command nobody knows about is a command nobody uses")
                    .anySatisfy(line -> assertThat(line).startsWith("give <item>"));
        }

        @Test
        @DisplayName("every sentence it can say has wording behind it")
        void itCanSpeak() {
            YamlConfiguration wording = YamlConfiguration.loadConfiguration(WORDING.toFile());
            // Rendered as their own key name otherwise — the failure that had a sponsor token announcing
            // "<sponsor-token-earned>" to a full server. Checked here because these six are all on the
            // refusal paths, which are exactly the ones nobody exercises before a tournament.
            for (String key : new String[] {"give-usage", "give-unknown-item", "give-bad-amount",
                    "give-nobody", "give-console-needs-a-player", "give-done"}) {
                assertThat(wording.isString("hungergames." + key))
                        .as("hungergames.%s has no line in messages.yml", key)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the amount is refused rather than clamped")
        void amountsAreRefused() {
            // The same rule /init follows, for the same reason: a clamp hands somebody a number they did not
            // choose, and they find out by looking at their hand.
            assertThat(source()).contains("amount < 1 || amount > MOST_AT_ONCE");
        }
    }

    @Nested
    @DisplayName("/hg shop")
    class Shop {

        @Test
        @DisplayName("there is no such subcommand")
        void itIsGone() {
            assertThat(source())
                    .as("the shop opens at a beacon; a command that opens it anywhere makes the beacon "
                            + "pointless")
                    .doesNotContain("case \"shop\"");
        }

        @Test
        @DisplayName("it is not suggested, described or documented either")
        void nothingPointsAtIt() {
            assertThat(hgCommand().options()).noneSatisfy(line ->
                    assertThat(line).startsWith("shop"));
            YamlConfiguration wording = YamlConfiguration.loadConfiguration(WORDING.toFile());
            assertThat(wording.isString("hungergames.help-shop"))
                    .as("a help line naming a command that does not exist is worse than a shorter help page")
                    .isFalse();
        }
    }

    private static ModuleCommand hgCommand() {
        return HungerGamesCommands.declared().stream()
                .filter(command -> command.name().equals("hg"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("/hg is not declared at all"));
    }

    private static String source() {
        try {
            return Files.readString(COMMAND, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
