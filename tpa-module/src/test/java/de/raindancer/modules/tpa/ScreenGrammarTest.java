package de.raindancer.modules.tpa;

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
 * That the screens keep to one grammar — the same rules the names, claims and moderation modules keep
 * to.
 *
 * <p>Repeated deliberately. The grammar exists so that a player who has learnt one of this server's
 * menus has learnt all of them, and a rule checked in one module and merely written down in the next is
 * a rule that lasts until the first hurried afternoon.
 */
class ScreenGrammarTest {

    private static final Path SCREENS = Path.of("src/main/java/de/raindancer/modules/tpa/screen");

    /** The interface: implemented rather than opened, so nothing constructs it. */
    private static final List<String> BASE_CLASSES = List.of("ITpaScreen");

    private record Screen(String name, String body) {
    }

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
        assertThat(screens()).extracting(Screen::name).contains(
                "BlockedMenu", "ITpaScreen", "RequestsMenu", "TpaHubMenu", "WhoToAskMenu");
        assertThat(drawnScreens()).isNotEmpty();

        // No ConfirmScreen here, unlike every other module — and that is right rather than missing.
        // Nothing on these pages is irreversible: unblocking somebody can be undone by blocking them
        // again, and refusing a request costs the asker one command. A confirmation in front of those
        // would be the sort people learn to click through, which is what makes the real ones useless.
        assertThat(screens()).extracting(Screen::name).doesNotContain("ConfirmScreen");
    }

    @Test
    @DisplayName("every screen that reads a right click says so on the button")
    void rightClicksAreAdvertised() {
        // Case-insensitively: WarpEditMenu's lore starts its sentence with "Right click", not "right
        // click", and a rule that missed a capital R would be a rule nobody could satisfy without
        // writing a sentence that reads oddly in the middle of a lore line.
        List<String> silent = new ArrayList<>();
        for (Screen screen : drawnScreens()) {
            String lowered = screen.body().toLowerCase(java.util.Locale.ROOT);
            if (screen.body().contains("isRightClick()") && !lowered.contains("right click")) {
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
                    && !screen.body().toLowerCase(java.util.Locale.ROOT).contains("shift")) {
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
                        + "the warp itself")
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
                    // A click that goes through a service which says something itself counts: the
                    // alternative is every screen repeating a line the service already sends, and two
                    // sentences for one action.
                    .containsAnyOf("send(", "tell(", ".open()", "asking()", "prefs()", "back()");
        }
    }

    @Test
    @DisplayName("no screen decides for itself who may use a warp")
    void screensAskTheServiceNotTheirOwnPermissionCheck() {
        // A screen greying a button, or a page deciding what it may show, has exactly two honest ways
        // to ask: services.access(), which is the rule, or services.catalogue().accessOf(...), which
        // reads what was actually stored. A screen that instead called hasPermission("...") or read
        // .permission().get() itself would be a second answer to "may they use this warp" — and
        // WarpAccessRule's own javadoc names that as the one mistake that opens the staff room, because
        // the second answer is the one nobody remembers to keep in step with the first.
        List<String> deciding = new ArrayList<>();
        for (Screen screen : drawnScreens()) {
            if (screen.body().contains("hasPermission(\"") || screen.body().contains(".permission().get")) {
                deciding.add(screen.name());
            }
        }
        assertThat(deciding)
                .as("these decide who may use a warp instead of asking services.access() or "
                        + "services.catalogue().accessOf(...) — a second answer to that question is "
                        + "the one that opens the staff room")
                .isEmpty();
    }
}
