package de.raindancer.modules.farmworld;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the screens keep to one grammar — the same rules the names, claims, moderation and warps modules keep to.
 *
 * <p>Repeated deliberately. The grammar exists so that a player who has learnt one of this server's menus has
 * learnt all of them, and a rule checked in one module and merely written down in the next is a rule that lasts
 * until the first hurried afternoon.
 *
 * <p>Here one of them is load-bearing rather than tidy: the button in the danger slot of the manage page deletes
 * three worlds and everything anybody built in them. {@link #theDangerSlotAlwaysConfirms} is the only automatic
 * check that it still asks first.
 */
class ScreenGrammarTest {

    private static final Path SCREENS = Path.of("src/main/java/de/raindancer/modules/farmworld/screen");

    /** The interface: implemented rather than opened, so nothing constructs it. */
    private static final List<String> BASE_CLASSES = List.of("IFarmWorldScreen");

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

    /**
     * The source with every string literal and comment emptied out.
     *
     * <p>For the rules that are about what the code <em>does</em>. A screen's lore is prose about the button
     * beside it, and prose describing a safeguard reads exactly like the safeguard — which is how the danger-slot
     * rule below came to pass a page whose confirmation had been deleted.
     */
    private static String withoutText(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ")
                .replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
    }

    @Test
    @DisplayName("the scan found the screens, so a rename cannot quietly empty it")
    void theScanIsNotVacuous() {
        assertThat(screens()).extracting(Screen::name).contains(
                "ConfirmScreen", "FarmWorldConfigMenu", "FarmWorldListMenu", "FarmWorldManageMenu",
                "FarmWorldMenu", "IFarmWorldScreen");
        assertThat(drawnScreens()).isNotEmpty();
    }

    @Test
    @DisplayName("every screen that reads a right click says so on the button")
    void rightClicksAreAdvertised() {
        // Case-insensitively: a lore line may start its sentence with "Right click", and a rule that missed a
        // capital R would be one nobody could satisfy without writing a sentence that reads oddly.
        List<String> silent = new ArrayList<>();
        for (Screen screen : drawnScreens()) {
            String lowered = screen.body().toLowerCase(Locale.ROOT);
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
                    && !screen.body().toLowerCase(Locale.ROOT).contains("shift")) {
                silent.add(screen.name());
            }
        }
        assertThat(silent).as("these read a shift click without ever saying so").isEmpty();
    }

    @Test
    @DisplayName("nothing irreversible happens without a confirmation")
    void theDangerSlotAlwaysConfirms() {
        // Scanned with the string literals taken out, and that is the whole of why this test is worth anything.
        //
        // The first version matched the word "confirm" anywhere in the 900 characters after `danger(` — and
        // passed when the confirmation was deleted, because the button's own lore says "This is the button the
        // confirmation exists for." The prose satisfied the rule that the code had stopped satisfying. Verified
        // by mutating the fix and watching it *not* fail, which is the only way that kind of hole is ever found.
        //
        // Case-insensitively on what is left, because a danger button may open the dialog inline —
        // `new ConfirmScreen(...)` — or hand off to a method named for it, `confirmRegenerate(farm)`, which is
        // what a button with four consequence lines has to do to stay readable.
        List<String> unguarded = new ArrayList<>();
        for (Screen screen : drawnScreens()) {
            String justCode = withoutText(screen.body()).toLowerCase(Locale.ROOT);
            int at = justCode.indexOf("danger(");
            while (at >= 0) {
                String call = justCode.substring(at, Math.min(justCode.length(), at + 900));
                if (!call.contains("confirm")) {
                    unguarded.add(screen.name());
                }
                at = justCode.indexOf("danger(", at + 1);
            }
        }
        assertThat(unguarded)
                .as("the danger slot is flanked by navigation, so a misclick must cost a page and not three "
                        + "worlds and everything anybody built in them")
                .isEmpty();
    }

    @Test
    @DisplayName("the one page that can delete worlds has exactly one danger button")
    void thereIsOnlyOneWayToDeleteAWorld() {
        // Not a style rule. Two buttons that both regenerate is two places for the confirmation to be added to,
        // and the one somebody forgets is the one in front of a delete.
        Screen managing = drawnScreens().stream()
                .filter(screen -> screen.name().equals("FarmWorldManageMenu"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the manage page is gone"));

        assertThat(managing.body().split("danger\\(", -1).length - 1)
                .as("one danger slot, one irreversible action")
                .isEqualTo(1);
        assertThat(managing.body())
                .as("and it is the one that makes the farm world again")
                .contains("confirmRegenerate");

        // The other half, because the rule above matches a method *named* for confirming. A method so named
        // that did not open the dialog would satisfy it and delete three worlds on one click, so what the
        // method actually does is checked here rather than taken from its name.
        int at = managing.body().indexOf("private void confirmRegenerate");
        assertThat(at).as("confirmRegenerate is gone").isPositive();
        assertThat(managing.body().substring(at, Math.min(managing.body().length(), at + 1200)))
                .as("confirmRegenerate has to open the dialog, not merely be called that")
                .contains("new ConfirmScreen(");
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
                .as("these build item stacks by hand instead of using Icons, which is how a server ends up "
                        + "with two ideas of what a button looks like")
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
                    .containsAnyOf("send(", "tell(", ".open()", "screens()", "travelling()");
        }
    }

    @Test
    @DisplayName("no screen decides for itself who may enter a farm world")
    void screensAskTheRuleNotTheirOwnPermissionCheck() {
        // A screen greying a button has exactly one honest way to ask: services.access(), which is the rule. A
        // screen that called hasPermission("...") itself would be a second answer to "may they enter this farm
        // world", and the second answer is the one that opens the donor world to everybody — because nobody
        // remembers to keep it in step with the first.
        List<String> deciding = new ArrayList<>();
        for (Screen screen : drawnScreens()) {
            if (screen.body().contains("hasPermission(\"")) {
                deciding.add(screen.name());
            }
        }
        assertThat(deciding)
                .as("these decide access instead of asking services.access()")
                .isEmpty();
    }

    @Test
    @DisplayName("a greyed button carries the module's own wording, not a sentence of its own")
    void refusalsAreNotWrittenTwice() {
        // The refusal in chat and the reason on the greyed button are the same fact. Written out in the screen
        // as well, an owner who rewords one gets a menu that says something different from the message — which
        // is the sort of small wrongness nobody reports and everybody notices.
        Screen list = drawnScreens().stream()
                .filter(screen -> screen.name().equals("FarmWorldListMenu"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the list page is gone"));

        assertThat(list.body())
                .as("the greyed lore comes from refusalKey and messages, not from a literal")
                .contains("refusalKey")
                .contains("messages()");
    }
}
