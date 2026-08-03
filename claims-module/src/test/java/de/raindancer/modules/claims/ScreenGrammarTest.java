package de.raindancer.modules.claims;

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
 * That the screens keep to one grammar.
 *
 * <p>These rules are the difference between a set of menus somebody learns once and a set they have to explore
 * every time — which is the complaint the whole rebuild started from. They are checked against the source
 * because the alternative is a running server and a person clicking things, and the source answers these
 * particular questions exactly.
 *
 * <p>The rules, and why each is worth a test:
 *
 * <ul>
 *   <li><b>A modifier nobody can see is a modifier nobody uses.</b> Every screen that reads a right click or a
 *       shift click says so in its own lore.</li>
 *   <li><b>Anything irreversible goes through a confirmation.</b> Not politeness: the danger slot sits between
 *       two navigation buttons, so a misclick has to cost a second page rather than the thing.</li>
 *   <li><b>Screens do not build their own item stacks.</b> Everything goes through Core's {@code Icons}, or the
 *       server ends up with two ideas of what a button looks like — which is exactly how five plugins came to
 *       look like five plugins.</li>
 *   <li><b>A refusal says something.</b> A button that fails silently is one a player presses four more times.</li>
 * </ul>
 */
class ScreenGrammarTest {

    private static final Path SCREENS =
            Path.of("src/main/java/de/raindancer/modules/claims/screen");

    /** Abstract screens: they are extended rather than opened, and they refuse on a subclass's behalf. */
    private static final List<String> BASE_CLASSES = List.of("ClaimScreen", "PermissionGrid");

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

    @Test
    @DisplayName("the scan found the screens, so a rename cannot quietly empty it")
    void theScanIsNotVacuous() {
        assertThat(screens()).extracting(Screen::name)
                .contains("ClaimMenu", "FlagsMenu", "MembersMenu", "AdminMenu", "ConfirmScreen");
        assertThat(screens()).hasSizeGreaterThanOrEqualTo(14);
    }

    @Test
    @DisplayName("every screen that reads a right click says so on the button")
    void rightClicksAreAdvertised() {
        List<String> silent = new ArrayList<>();
        for (Screen screen : screens()) {
            boolean reads = screen.body().contains("isRightClick()");
            boolean says = screen.body().contains("right click");
            if (reads && !says) {
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
        for (Screen screen : screens()) {
            if (screen.body().contains("isShiftClick()") && !screen.body().toLowerCase().contains("shift")) {
                silent.add(screen.name());
            }
        }
        assertThat(silent).as("these read a shift click without ever saying so").isEmpty();
    }

    @Test
    @DisplayName("nothing irreversible happens without a confirmation")
    void theDangerSlotAlwaysConfirms() {
        List<String> unguarded = new ArrayList<>();
        for (Screen screen : screens()) {
            int at = screen.body().indexOf("danger(");
            while (at >= 0) {
                // The handler of a danger button must open a confirmation and do nothing else. Looking at the
                // whole call rather than the line, because the button spans several.
                String call = screen.body().substring(at, Math.min(screen.body().length(), at + 900));
                if (!call.contains("ConfirmScreen")) {
                    unguarded.add(screen.name());
                }
                at = screen.body().indexOf("danger(", at + 1);
            }
        }
        assertThat(unguarded)
                .as("the danger slot is flanked by navigation, so a misclick must cost a page and not the "
                        + "thing itself — these do something irreversible directly")
                .isEmpty();
    }

    @Test
    @DisplayName("screens build their buttons through Core rather than by hand")
    void nobodyBuildsTheirOwnItemStacks() {
        List<String> rolling = new ArrayList<>();
        for (Screen screen : screens()) {
            // A screen may clone an item it is displaying — a bank stack, a pantry stack — which is not the
            // same as inventing a button.
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
    @DisplayName("a screen that refuses says why")
    void refusalsAreSpokenAloud() {
        List<String> mute = new ArrayList<>();
        for (Screen screen : screens()) {
            if (BASE_CLASSES.contains(screen.name())) {
                continue;
            }
            boolean refuses = screen.body().contains("canManage(") || screen.body().contains("isServerAdmin(");
            // Either it says something, or it greys the button and puts the reason in the lore. Both are
            // answers; silence is not. Listing the ways rather than one magic string, because a screen that
            // greys a button has already explained itself before the click.
            boolean speaks = screen.body().contains("messages().send(")
                    || screen.body().contains("tell(")
                    || screen.body().contains("Icons.locked(")
                    || screen.body().contains("\"The owner's to change\"")
                    || screen.body().contains("\"The server");
            if (refuses && !speaks) {
                mute.add(screen.name());
            }
        }
        assertThat(mute)
                .as("these check a permission and never say anything when it fails — a button that does "
                        + "nothing silently is one a player presses four more times")
                .isEmpty();
    }

    @Test
    @DisplayName("every screen is reachable from another one or from the opener")
    void nothingIsOrphaned() {
        List<Screen> all = screens();
        String everything = String.join("\n", all.stream().map(Screen::body).toList())
                + readOpener();

        List<String> orphans = new ArrayList<>();
        for (Screen screen : all) {
            if (BASE_CLASSES.contains(screen.name())) {
                continue;   // never opened themselves; their subclasses are
            }
            // Its own file does not count as a reference to it.
            String elsewhere = everything.replace(screen.body(), "");
            // Qualified too: the module wires its screens up with their full names.
            if (!elsewhere.contains("new " + screen.name() + "(")
                    && !elsewhere.contains("." + screen.name() + "(")) {
                orphans.add(screen.name());
            }
        }
        assertThat(orphans)
                .as("these screens exist and nothing opens them, so they are either dead or a menu is "
                        + "missing its button")
                .isEmpty();
    }

    /** The module's own wiring, which is the only other place a screen is constructed. */
    private static String readOpener() {
        try {
            return Files.readString(
                    Path.of("src/main/java/de/raindancer/modules/claims/ClaimsModule.java"));
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module", unreadable);
        }
    }
}
