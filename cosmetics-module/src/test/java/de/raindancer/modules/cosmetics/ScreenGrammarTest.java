package de.raindancer.modules.cosmetics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the screens keep to one grammar — the same rules the claims and moderation modules keep to.
 *
 * <p>Repeated deliberately. The grammar exists so that a player who has learnt one of this server's
 * menus has learnt all of them, and a rule checked in one module and merely written down in the next is
 * a rule that lasts until the first hurried afternoon.
 */
class ScreenGrammarTest {

    private static final Path SCREENS = Path.of("src/main/java/de/raindancer/modules/cosmetics/screen");

    /** The interface: implemented rather than opened, so nothing constructs it. */
    private static final List<String> BASE_CLASSES = List.of("ICosmeticsScreen");

    private record Screen(String name, String body) {

        /**
         * What a player can read on it: the words in the source, and the wording of every messages.yml key
         * it names — a screen whose lore lives in messages.yml, as it should, says its hints there.
         */
        String readable() {
            StringBuilder words = new StringBuilder(body);
            java.util.regex.Matcher key = java.util.regex.Pattern.compile("\"(cosmetics\\.[a-z0-9.-]+)\"")
                    .matcher(body);
            while (key.find()) {
                Object value = WORDING.get(key.group(1));
                if (value != null) {
                    words.append('\n').append(value);
                }
            }
            return words.toString().replace('-', ' ');
        }
    }

    private static final org.bukkit.configuration.file.YamlConfiguration WORDING =
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.File("src/main/resources/de/raindancer/modules/cosmetics/messages.yml"));

    private static List<Screen> screens() {
        try (Stream<Path> files = Files.list(SCREENS)) {
            List<Screen> found = new ArrayList<>();
            for (Path file : files.sorted().toList()) {
                found.add(new Screen(file.getFileName().toString().replace(".java", ""),
                        Files.readString(file)));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the screen package", unreadable);
        }
    }

    private static List<Screen> drawnScreens() {
        return screens().stream().filter(screen -> !BASE_CLASSES.contains(screen.name())).toList();
    }

    @Test
    @DisplayName("the scan found the screens, so a rename cannot quietly empty it")
    void theScanIsNotVacuous() {
        assertThat(screens()).extracting(Screen::name).contains("NameStyleMenu", "ICosmeticsScreen");
        assertThat(drawnScreens()).isNotEmpty();
    }

    @Test
    @DisplayName("every screen that reads a right click says so on the button")
    void rightClicksAreAdvertised() {
        List<String> silent = new ArrayList<>();
        for (Screen screen : drawnScreens()) {
            if (screen.body().contains("isRightClick()") && !screen.readable().toLowerCase().contains("right click")) {
                silent.add(screen.name());
            }
        }
        assertThat(silent)
                .as("these read a right click and never mention one in their lore, so nobody will find it")
                .isEmpty();
    }

    @Test
    @DisplayName("every screen that reads a shift click says so too")
    void shiftClicksAreAdvertised() {
        List<String> silent = new ArrayList<>();
        for (Screen screen : drawnScreens()) {
            if (screen.body().contains("isShiftClick()")
                    && !screen.readable().replace("isShiftClick", "").toLowerCase().contains("shift")) {
                silent.add(screen.name());
            }
        }
        assertThat(silent).as("these read a shift click without ever saying so").isEmpty();
    }

    @Test
    @DisplayName("nothing irreversible happens without a confirmation")
    void theDangerSlotAlwaysConfirms() {
        List<String> unguarded = new ArrayList<>();
        for (Screen screen : drawnScreens()) {
            int at = screen.body().indexOf("danger(");
            while (at >= 0) {
                String call = screen.body().substring(at, Math.min(screen.body().length(), at + 900));
                if (!call.contains("Confirm")) {
                    unguarded.add(screen.name());
                }
                at = screen.body().indexOf("danger(", at + 1);
            }
        }
        assertThat(unguarded)
                .as("the danger slot is flanked by navigation, so a misclick must cost a page and not "
                        + "the thing itself")
                .isEmpty();
    }

    @Test
    @DisplayName("screens build their buttons through Core rather than by hand")
    void nobodyBuildsTheirOwnItemStacks() {
        List<String> rolling = new ArrayList<>();
        for (Screen screen : drawnScreens()) {
            if (screen.body().contains("new ItemStack(") || screen.body().contains(".setItemMeta(new ")) {
                rolling.add(screen.name());
            }
        }
        assertThat(rolling)
                .as("these build item stacks by hand instead of using Icons, which is how a server ends "
                        + "up with two ideas of what a button looks like")
                .isEmpty();
    }

    @Test
    @DisplayName("a screen does not judge a style itself")
    void screensAskTheService() {
        // The button the screen greys and the refusal the service sends must be one answer.
        List<String> deciding = new ArrayList<>();
        for (Screen screen : drawnScreens()) {
            if (screen.body().contains("new NameStyleRule(") || screen.body().contains("hasPermission(")) {
                deciding.add(screen.name());
            }
        }
        assertThat(deciding).as("these decide who may wear what instead of asking the service").isEmpty();
    }

    @Test
    @DisplayName("a list with an empty state says what to do about it")
    void anEmptyListIsNotABlankWindow() {
        for (Screen screen : drawnScreens()) {
            if (!screen.body().contains("PaginatedMenu")) {
                continue;
            }
            assertThat(screen.body())
                    .as("%s is a list and does not say what an empty one means", screen.name())
                    .contains("emptyIcon()");
        }
    }

    @Test
    @DisplayName("a button that does nothing at all is the one a player presses twice")
    void everyEntryAnswers() {
        for (Screen screen : drawnScreens()) {
            if (!screen.body().contains("protected void onClick(")) {
                continue;
            }
            int at = screen.body().indexOf("protected void onClick(");
            String body = screen.body().substring(at, Math.min(screen.body().length(), at + 700));
            assertThat(body)
                    .as("%s has an entry whose click says nothing and opens nothing", screen.name())
                    .containsAnyOf("send(", "tell(", ".open()", "wear(", "backToWhoeverOpenedThis()");
        }
    }
}
