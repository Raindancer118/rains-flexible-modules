package de.raindancer.modules.moderation;

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
 * That the screens keep to one grammar — the same five rules the claims module keeps to.
 *
 * <p>Repeated deliberately. The grammar exists so that a player who has learnt one of this server's
 * menus has learnt all of them, and a rule checked in one module and merely written down in the next is
 * a rule that lasts until the first hurried afternoon.
 *
 * <p>The moderation screens have one more reason to care than most: half of their buttons are things
 * somebody may not do, and every one of those is a chance to hide a button instead of explaining it.
 */
class ScreenGrammarTest {

    private static final Path SCREENS =
            Path.of("src/main/java/de/raindancer/modules/moderation/screen");

    /** The interface and the abstract screens: extended rather than opened, so nothing constructs them. */
    private static final List<String> BASE_CLASSES =
            List.of("IModerationScreen", "ModerationScreen", "ModerationList");

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
                .contains("PlayerMenu", "PunishMenu", "HistoryMenu", "ReportsMenu", "ConfirmScreen");
        assertThat(screens()).hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("every screen that reads a right click says so on the button")
    void rightClicksAreAdvertised() {
        List<String> silent = new ArrayList<>();
        for (Screen screen : screens()) {
            if (screen.body().contains("isRightClick()") && !screen.body().contains("right click")) {
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
                String call = screen.body().substring(at, Math.min(screen.body().length(), at + 900));
                if (!call.contains("ConfirmScreen")) {
                    unguarded.add(screen.name());
                }
                at = screen.body().indexOf("danger(", at + 1);
            }
        }
        assertThat(unguarded)
                .as("the danger slot is flanked by navigation, so a misclick must cost a page and not "
                        + "the thing itself — these do something irreversible directly")
                .isEmpty();
    }

    @Test
    @DisplayName("screens build their buttons through Core rather than by hand")
    void nobodyBuildsTheirOwnItemStacks() {
        List<String> rolling = new ArrayList<>();
        for (Screen screen : screens()) {
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
    @DisplayName("a band screen that asks about a permission greys the button rather than hiding it")
    void nothingIsHiddenFromTheViewer() {
        // Hiding makes the menu a different shape per viewer, so nobody can be told "the third one
        // along", and "why can I not see it" has no answer on screen.
        //
        // The greyed form is Menu's six-argument overload —
        //     band(band, column, allowed, item, reason, handler)
        // — so the permission reaches the *layout* rather than an `if` around the button. Counted by
        // parsing the arguments rather than by looking for `, may(`, because a screen is free to hoist
        // the answer into a local first and ReportMenu does exactly that.
        List<String> hiding = new ArrayList<>();
        for (Screen screen : screens()) {
            if (BASE_CLASSES.contains(screen.name()) || !drawsBands(screen)) {
                continue;   // the lists have no bands; see the next test for what they must do
            }
            if (screen.body().contains("may(") && !hasGuardedBand(screen)
                    && !screen.body().contains("Icons.locked(")) {
                hiding.add(screen.name());
            }
        }
        assertThat(hiding)
                .as("these decide what to show from a permission without ever greying anything — a "
                        + "button that is simply absent has no explanation on screen")
                .isEmpty();
    }

    @Test
    @DisplayName("a list re-asks when an entry is clicked, because a page can be open for minutes")
    void listsRecheckOnTheClick() {
        // A list has no per-entry permission to grey — its content is the entries, and every one of
        // them is drawn the same way. What it must not do is act on a click without asking again: the
        // render happened at least one click ago and a permission can be taken away in between.
        List<String> trusting = new ArrayList<>();
        for (Screen screen : screens()) {
            if (BASE_CLASSES.contains(screen.name()) || drawsBands(screen)) {
                continue;
            }
            if (!screen.body().contains("may(")) {
                continue;   // nothing about this list depends on a permission at all
            }
            int onClick = screen.body().indexOf("protected void onClick(");
            String handler = onClick < 0 ? ""
                    : screen.body().substring(onClick,
                    Math.min(screen.body().length(), onClick + 900));
            if (!handler.contains("may(") || !handler.contains("tell(")) {
                trusting.add(screen.name());
            }
        }
        assertThat(trusting)
                .as("these act on a click without re-asking and saying so — the greyed state of a "
                        + "page rendered five minutes ago is not a permission check")
                .isEmpty();
    }

    /** Whether this screen lays buttons out in bands at all, as opposed to being a paged list. */
    private static boolean drawsBands(Screen screen) {
        return screen.body().contains("band(MenuLayout.");
    }

    /**
     * Whether any {@code band(...)} call here uses the six-argument, greying overload.
     *
     * <p>Counts commas at bracket depth one, so a nested {@code Icons.of(a, b, c)} inside an argument
     * does not read as three arguments of the band call itself. That nesting is the norm rather than
     * the exception here, which is why the naive split does not work.
     */
    private static boolean hasGuardedBand(Screen screen) {
        String body = screen.body();
        for (int at = body.indexOf("band("); at >= 0; at = body.indexOf("band(", at + 1)) {
            if (argumentsOf(body, at + "band(".length()) >= 6) {
                return true;
            }
        }
        return false;
    }

    /** How many arguments the call whose bracket opened at {@code from} takes. */
    private static int argumentsOf(String body, int from) {
        int depth = 0;
        int arguments = 1;
        for (int at = from; at < body.length(); at++) {
            char here = body.charAt(at);
            if (here == '(' || here == '[') {
                depth++;
            } else if (here == ')' || here == ']') {
                if (depth == 0) {
                    return arguments;
                }
                depth--;
            } else if (here == ',' && depth == 0) {
                arguments++;
            }
        }
        return arguments;
    }

    @Test
    @DisplayName("a screen that refuses says why")
    void refusalsAreSpokenAloud() {
        List<String> mute = new ArrayList<>();
        for (Screen screen : screens()) {
            if (BASE_CLASSES.contains(screen.name())) {
                continue;
            }
            boolean refuses = screen.body().contains("canAct(") || screen.body().contains("may(");
            boolean speaks = screen.body().contains("tell(")
                    || screen.body().contains("messages().send(")
                    || screen.body().contains("Icons.locked(");
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
    @DisplayName("a screen opened from another screen is told where it came from")
    void everySubScreenHasAParent() {
        // Core's Menu paints the Back button from its parent, so a screen constructed with a null
        // parent is a dead end: the player's only way out is Close, and they lose the page they were
        // on. Live on mc-test the notes page had no Back at all, and neither did any other page —
        // every screen was being opened through the opener, which has no parent to pass.
        //
        // Hence the rule: inside the screen package, a screen opens its children *directly* and hands
        // itself over. The opener is for the ways in from a command, where there is nothing to go
        // back to.
        List<String> deadEnds = new ArrayList<>();
        for (Screen screen : screens()) {
            for (int at = screen.body().indexOf("new "); at >= 0;
                 at = screen.body().indexOf("new ", at + 1)) {
                String call = screen.body().substring(at,
                        Math.min(screen.body().length(), at + 200));
                if (!call.matches("(?s)new [A-Za-z]*(Menu|Screen)\\(.*")) {
                    continue;
                }
                // The parent is the third argument of every screen in this package.
                String[] arguments = call.substring(call.indexOf('(') + 1).split(",");
                if (arguments.length >= 3 && arguments[2].trim().startsWith("null")) {
                    deadEnds.add(screen.name() + " opens " + call.substring(4, call.indexOf('('))
                            + " with no parent");
                }
            }
            if (screen.body().contains("services().screens().")) {
                deadEnds.add(screen.name() + " goes through the opener, which cannot pass a parent");
            }
        }
        assertThat(deadEnds)
                .as("these leave the player with no way back but Close")
                .isEmpty();
    }

    @Test
    @DisplayName("every screen is reachable from another one or from the opener")
    void nothingIsOrphaned() {
        List<Screen> all = screens();
        String everything = String.join("\n", all.stream().map(Screen::body).toList()) + readOpener();

        List<String> orphans = new ArrayList<>();
        for (Screen screen : all) {
            if (BASE_CLASSES.contains(screen.name())) {
                continue;   // never opened themselves; their subclasses are
            }
            String elsewhere = everything.replace(screen.body(), "");
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

    @Test
    @DisplayName("the opener holds only ways in that something actually uses")
    void theOpenerHasNoDeadDoors() {
        // The opener exists to break the cycle between the commands and the screens — a command asks
        // for a page without knowing what a page is. Once the screens open each other directly (see
        // the test above), any method here that nothing calls is a door onto a corridor: it still
        // compiles, it still has to be implemented in LiveScreens, and it is the obvious thing for the
        // next person to reach for — which would quietly reintroduce the parentless page.
        String openerSource = read("ModerationScreensOpener.java");
        String everythingElse = String.join("\n", screens().stream().map(Screen::body).toList())
                + readOpener() + readCommands();

        List<String> unused = new ArrayList<>();
        for (String line : openerSource.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("void ") || !trimmed.endsWith(");")) {
                continue;
            }
            String method = trimmed.substring("void ".length(), trimmed.indexOf('('));
            if (!everythingElse.contains("screens()." + method + "(")) {
                unused.add(method);
            }
        }
        assertThat(unused)
                .as("nothing calls these, so they are doors onto a corridor — and the next person to "
                        + "use one gets a page with no way back")
                .isEmpty();
    }

    private static String read(String fileName) {
        try {
            return Files.readString(
                    Path.of("src/main/java/de/raindancer/modules/moderation").resolve(fileName));
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + fileName, unreadable);
        }
    }

    /** The commands, which are the other half of what may call the opener. */
    private static String readCommands() {
        Path commands = Path.of("src/main/java/de/raindancer/modules/moderation/command");
        try (Stream<Path> files = Files.list(commands)) {
            StringBuilder everything = new StringBuilder();
            for (Path file : files.sorted().toList()) {
                everything.append(Files.readString(file)).append('\n');
            }
            return everything.toString();
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the commands", unreadable);
        }
    }

    /** The module's own wiring, which is the only other place a screen is constructed. */
    private static String readOpener() {
        try {
            return Files.readString(
                    Path.of("src/main/java/de/raindancer/modules/moderation/ModerationModule.java"));
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module", unreadable);
        }
    }
}
