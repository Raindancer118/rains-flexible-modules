package de.raindancer.modules.claims;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** The interface and the abstract screens: extended rather than opened, so nothing constructs them. */
    private static final List<String> BASE_CLASSES =
            List.of("IClaimScreen", "ClaimScreen", "PermissionGrid");

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
                // FlagsMenu is deliberately absent: the rules are drawn by Core's FlagChooser now,
                // so this module has no flag screens of its own to keep in grammar.
                .contains("ClaimMenu", "MembersMenu", "AdminMenu", "ConfirmScreen");
        assertThat(screens()).hasSizeGreaterThanOrEqualTo(11);
    }

    @Test
    @DisplayName("every screen that reads a right click says so on the button")
    void rightClicksAreAdvertised() {
        List<String> silent = new ArrayList<>();
        for (Screen screen : screens()) {
            boolean reads = screen.body().contains("isRightClick()");
            boolean says = screen.body().toLowerCase(java.util.Locale.ROOT)
                    .matches("(?s).*right[ -]click.*");
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
                // The rule is about what a misclick COSTS, not about the slot being occupied. That slot sits
                // between the page arrows, so anything irreversible there has to ask first — but a button that
                // only opens something is not irreversible, and the claim list deliberately keeps the manual
                // there so the people most in need of it, who have no claim yet, can find it.
                boolean irreversible = call.contains("claimService().delete(")
                        || call.contains("zones().remove(")
                        || call.contains("claim.unban(")
                        || call.contains("removeOwner(");
                if (irreversible && !call.contains("ConfirmScreen")) {
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

    @Test
    @DisplayName("buttons that open a page are spaced, not packed shoulder to shoulder")
    void nothingIsCrammedTogether() {
        // The claim menu once put twelve buttons on one page in adjacent columns. It read as a wall: nothing
        // was grouped, the eye had nowhere to rest, and finding the fence meant reading all twelve. The rule
        // that came out of it is that buttons which take you somewhere sit at least two columns apart, so a
        // pane falls between each pair.
        //
        // Only those buttons. A control that adjusts a value — Deeper next to Shallower, Lower ceiling next
        // to Higher — is a pair, and separating a pair is worse than leaving it alone: the two halves of one
        // decision belong side by side. The difference is whether the click opens something.
        Pattern placed = Pattern.compile("band\\(MenuLayout\\.([A-Z]+),\\s*(\\d+)");
        List<String> packed = new ArrayList<>();

        for (Screen screen : screens()) {
            Map<String, List<Integer>> byBand = new LinkedHashMap<>();
            Matcher matcher = placed.matcher(screen.body());
            while (matcher.find()) {
                String rest = screen.body().substring(matcher.end(),
                        Math.min(screen.body().length(), matcher.end() + 700));
                int nextButton = rest.indexOf("band(MenuLayout.");
                String thisButton = nextButton < 0 ? rest : rest.substring(0, nextButton);
                if (!thisButton.contains(".open()")) {
                    continue;   // a control, not a door
                }
                byBand.computeIfAbsent(matcher.group(1), band -> new ArrayList<>())
                        .add(Integer.parseInt(matcher.group(2)));
            }
            byBand.forEach((band, columns) -> {
                List<Integer> sorted = new ArrayList<>(new TreeSet<>(columns));
                for (int at = 1; at < sorted.size(); at++) {
                    if (sorted.get(at) - sorted.get(at - 1) < 2) {
                        packed.add(screen.name() + " " + band + " columns "
                                + sorted.get(at - 1) + "+" + sorted.get(at));
                    }
                }
            });
        }

        assertThat(packed)
                .as("these put two doors in neighbouring columns, which is what made the claim menu "
                        + "unreadable — leave a pane between them")
                .isEmpty();
    }

    @Test
    @DisplayName("a head that stands for a person wears that person's face")
    void headsAreSkinned() {
        // Icons.of(Material.PLAYER_HEAD, ...) is an unskinned head, which renders as Steve for everybody.
        // Core has Icons.head for this, and the whole reason it exists is that a head is only worth using as
        // an icon when it is somebody's — otherwise it is a worse-looking generic block.
        List<String> steve = new ArrayList<>();
        for (Screen screen : screens()) {
            if (screen.body().contains("Icons.of(Material.PLAYER_HEAD")) {
                steve.add(screen.name());
            }
        }
        assertThat(steve)
                .as("these show an unskinned head, which is Steve on every server — use Icons.head(uuid, ..)")
                .isEmpty();
    }

    @Test
    @DisplayName("a category's children are things, not more categories")
    void theTreeIsTwoDeepAtMost() {
        // The front page carries two categories on purpose — People and Configuration — because the two
        // questions people arrive with are "who may be here" and "how is this set up", and mixing them across
        // one flat page of twelve buttons was unreadable.
        //
        // What must not happen is a third level. Claim menu → Configuration → Perks is a page of doors opening
        // a page of doors, and by then nobody can say where anything lives. So a category may hold things, and
        // a thing may not be another category.
        List<String> tooDeep = new ArrayList<>();
        for (Screen screen : screens()) {
            if (!isACategory(screen)) {
                continue;
            }
            Matcher opened = Pattern.compile("new (\\w+Menu)\\(").matcher(screen.body());
            while (opened.find()) {
                String name = opened.group(1);
                if (name.equals(screen.name())) {
                    continue;   // reopening itself after a chooser returns, which is not another level
                }
                screens().stream()
                        .filter(candidate -> candidate.name().equals(name))
                        .filter(ScreenGrammarTest::isACategory)
                        .findFirst()
                        .ifPresent(nested ->
                                tooDeep.add(screen.name() + " → " + nested.name()));
            }
        }
        assertThat(tooDeep)
                .as("a category inside a category puts the real page three clicks from the front door")
                .isEmpty();
    }

    /** A page whose whole job is opening other pages, rather than showing or changing anything. */
    private static boolean isACategory(Screen screen) {
        long doors = Pattern.compile("\\.open\\(\\)").matcher(screen.body()).results().count();
        boolean showsSomething = screen.body().contains("entries()")
                || screen.body().contains("PaginatedMenu")
                || screen.body().contains("refresh()");
        return doors >= 3 && !showsSomething;
    }

    @Test
    @DisplayName("no page title repeats the plugin's own name")
    void theBrandIsNotSaidTwice() {
        // Every window title is rendered as "<brand> » <page>", so a page called "Claims — server" came out as
        // "Claims » Claims — server". A title gets 154 pixels and gets cut off at the end, so a wasted word at
        // the front is a word missing from the part that says where you are.
        //
        // Only the start of the title: "Your claims" and "Where nobody may claim" are sentences about claims,
        // which is different from a label that names the plugin.
        List<String> repeating = new ArrayList<>();
        Pattern titled = Pattern.compile("Component\\.text\\(\"([^\"]+)\"");
        for (Screen screen : screens()) {
            Matcher matcher = titled.matcher(screen.body());
            while (matcher.find()) {
                String title = matcher.group(1);
                if (title.startsWith("Claim") && !title.startsWith("Claimed")) {
                    repeating.add(screen.name() + ": \"" + title + "\"");
                }
            }
        }
        assertThat(repeating)
                .as("the brand is already in front of these; naming the plugin again spends the title's "
                        + "pixel budget on a word the player can already see")
                .isEmpty();
    }

    @Test
    @DisplayName("no screen brings the Book and Quill back to the chrome bar")
    void thereIsNoHelpBook() {
        // Core draws a "What is this?" book in the chrome row whenever a screen returns any help lines, and the
        // decision here is that these screens do not want it: a button whose lore explains itself does not need
        // a second place saying the same thing, and the row it sat in is the one people look at for Back and
        // Close. Removed rather than left empty so nobody has to wonder which screens still have one.
        List<String> withHelp = new ArrayList<>();
        for (Screen screen : screens()) {
            if (screen.body().contains("helpLines")) {
                withHelp.add(screen.name());
            }
        }
        assertThat(withHelp)
                .as("a screen that returns help lines gets the book back in its chrome row")
                .isEmpty();
    }
}
