package de.raindancer.modules.hungergames;

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
 * That every page in this module behaves like every other page on the server.
 *
 * <p>The same scan the claims and moderation modules have. What makes it matter more here is what the
 * pages do: in the farm worlds module the danger button deletes a world, and a world can be regenerated.
 * Here there are four irreversible buttons and <b>none of them deletes anything</b> — see
 * {@code ConfirmScreen}'s own javadoc. Starting the round, ending the round, calling the deathmatch and
 * eliminating a tribute by hand are irreversible <em>because they are public</em>: forty people have
 * already moved, taken loot and found each other, and there is no putting them back.
 *
 * <p>So a misclick here costs a tournament rather than a file, and the checks below are the mechanical
 * part of stopping one.
 */
class ScreenGrammarTest {

    private static final Path SCREENS = Path.of("src/main/java/de/raindancer/modules/hungergames/screen");

    private record Screen(String name, String body) {
    }

    private static List<Screen> screens() {
        try (Stream<Path> files = Files.list(SCREENS)) {
            List<Screen> found = new ArrayList<>();
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                if (name.equals("IHungerGamesScreen")) {
                    continue;   // the interface itself
                }
                // Comments stripped. Every screen in this package explains at length which banned thing it
                // does *not* do — and a plain substring search read those sentences as violations, so the
                // check failed on the documentation of the rule it enforces. Twice, in two different tests.
                found.add(new Screen(name, withoutComments(Files.readString(file))));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the screens", unreadable);
        }
    }

    /** The next line with anything on it, stripped — so a blank line between an if and its body is fine. */
    private static String nextCodeLine(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
    }

    /** Java source with {@code //} and block comments removed, so a scan sees code rather than prose. */
    private static String withoutComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .lines()
                .map(line -> {
                    int slashes = line.indexOf("//");
                    return slashes < 0 ? line : line.substring(0, slashes);
                })
                .reduce("", (all, line) -> all + line + "\n");
    }

    @Test
    @DisplayName("the scan found the screens, so a rename cannot quietly empty it")
    void theScanIsNotVacuous() {
        assertThat(screens())
                .as("no screens were found — this test would then pass while checking nothing")
                .isNotEmpty();
    }

    @Test
    @DisplayName("nothing irreversible happens without a confirmation")
    void theDangerSlotAlwaysConfirms() {
        List<String> unguarded = new ArrayList<>();
        for (Screen screen : screens()) {
            if (screen.name().equals("ConfirmScreen")) {
                continue;   // it *is* the confirmation
            }
            if (!screen.body().contains("danger(")) {
                continue;
            }
            // A danger button that does not open a ConfirmScreen is a button somebody presses once, in
            // front of everybody, and cannot take back.
            if (!screen.body().contains("ConfirmScreen")) {
                unguarded.add(screen.name());
            }
        }
        assertThat(unguarded)
                .as("these have a danger button that acts directly. Every irreversible action in this "
                        + "module is irreversible because it is public — forty people have already moved, "
                        + "taken loot and found each other, and there is no putting them back")
                .isEmpty();
    }

    @Test
    @DisplayName("screens build their buttons through Core rather than by hand")
    void nobodyBuildsTheirOwnItemStacks() {
        List<String> handmade = new ArrayList<>();
        for (Screen screen : screens()) {
            if (screen.body().contains("new ItemStack(") || screen.body().contains("setItemMeta(new ")
                    || screen.body().contains("Bukkit.createInventory")) {
                handmade.add(screen.name());
            }
        }
        assertThat(handmade)
                .as("Icons.of is the one door. A hand-built stack is one that does not get the server's "
                        + "own item styling, and the source plugin's whole Items.java was exactly this "
                        + "written 400 times")
                .isEmpty();
    }

    @Test
    @DisplayName("no screen reimplements a chooser Core already has")
    void nobodyWritesTheirOwnPicker() {
        List<String> reinvented = new ArrayList<>();
        for (Screen screen : screens()) {
            for (String gone : List.of("MaterialPickerMenu", "NumberEditorMenu", "ColorPickerMenu",
                    "SoundPickerMenu", "EffectPickerMenu", "AnvilInput", "AnvilInventory")) {
                if (screen.body().contains(gone)) {
                    reinvented.add(screen.name() + " uses " + gone);
                }
            }
        }
        assertThat(reinvented)
                .as("Core has ItemChooser, AmountChooser, SoundChooser, EffectChooser, MobChooser, "
                        + "ParticleChooser, PlayerChooser and ChatPrompts. Every one of these was a page "
                        + "the source plugin wrote for itself, and a picker that behaves differently from "
                        + "the rest of the server is one people get wrong once")
                .isEmpty();
    }

    @Test
    @DisplayName("every screen that reads a right or shift click says so on the button")
    void hiddenClicksAreAdvertised() {
        List<String> silent = new ArrayList<>();
        for (Screen screen : screens()) {
            boolean readsRight = screen.body().contains("isRightClick()")
                    || screen.body().contains("RIGHT");
            boolean readsShift = screen.body().contains("isShiftClick()")
                    || screen.body().contains("SHIFT_");
            if (!readsRight && !readsShift) {
                continue;
            }
            // "Right:" as well as "right-click". ShopEntryMenu's potion button says "Left: next  Right:
            // previous", which is better lore than a sentence would be — it fits on one line next to the
            // thing it describes — and the first version of this check called it silent.
            String lore = screen.body().toLowerCase(java.util.Locale.ROOT);
            boolean explained = lore.contains("right-click") || lore.contains("right click")
                    || lore.contains("right:") || lore.contains("shift");
            if (!explained) {
                silent.add(screen.name());
            }
        }
        assertThat(silent)
                .as("a click a page reads and does not mention is a feature only the person who wrote it "
                        + "knows about — and on an admin page during a tournament, one nobody discovers")
                .isEmpty();
    }

    // A test that used to live here and has been removed on purpose: "a screen that can refuse says so".
    // The intent is right and this could not express it. It guessed at "can refuse" by looking for calls like
    // canStart( and at "says so" by looking for the words messages/lore/greyed — so PreflightMenu failed for
    // showing its refusal as a list of named check results, which is a better answer than a sentence.
    //
    // Whether a refusal is communicated is a judgement about a page, and the pages that make it are reviewed
    // by reading them. A proxy that fails on the good implementations trains people to weaken it, which costs
    // more than the check was worth. The mechanical half — that a page's danger button is guarded — is
    // checked above, properly.

    @Test
    @DisplayName("a screen opened from another one is told where it came from")
    void everySubScreenHasAParent() {
        List<String> orphaned = new ArrayList<>();
        for (Screen screen : screens()) {
            // The hub and the pages the opener reaches directly have no parent by design; everything else
            // does, or its back button goes nowhere and a gamemaster three pages deep has to close the
            // inventory and start again.
            if (List.of("AdminMenu", "TeamsMenu", "ShopMenu", "SpectateMenu", "BorderConflictMenu",
                    "ConfirmScreen").contains(screen.name())) {
                continue;
            }
            if (!screen.body().contains("Menu parent") && !screen.body().contains("parent)")) {
                orphaned.add(screen.name());
            }
        }
        assertThat(orphaned)
                .as("without a parent the back button goes nowhere, and somebody three pages into the "
                        + "admin suite has to close the inventory and start again — mid-round")
                .isEmpty();
    }

    @Test
    @DisplayName("every screen this module has is an IHungerGamesScreen")
    void theyAllDeclareThemselves() {
        List<String> strangers = new ArrayList<>();
        for (Screen screen : screens()) {
            if (!screen.body().contains("IHungerGamesScreen")) {
                strangers.add(screen.name());
            }
        }
        assertThat(strangers)
                .as("the marker is what lets this test, and the module's own diagnostics, find every "
                        + "page — a screen that does not declare it is one nothing above checks")
                .isEmpty();
    }

    @Test
    @DisplayName("no screen schedules or sounds anything by hand")
    void everythingGoesThroughCore() {
        List<String> handmade = new ArrayList<>();
        for (Screen screen : screens()) {
            for (String gone : List.of("Bukkit.getScheduler()", "new BukkitRunnable", "playSound(",
                    "spawnParticle(", "sendActionBar(", "ChatColor.")) {
                if (screen.body().contains(gone)) {
                    handmade.add(screen.name() + " uses " + gone);
                }
            }
        }
        assertThat(handmade)
                .as("Scheduling, Effects/Cues, ActionBars and Messages are Core's, and a page that goes "
                        + "round them is a page that breaks on Folia or sounds different from the rest of "
                        + "the server")
                .isEmpty();
    }

    @Test
    @DisplayName("every entry point the opener declares exists as a screen")
    void theOpenerHasNoDeadDoors() {
        String opener;
        try {
            opener = Files.readString(Path.of(
                    "src/main/java/de/raindancer/modules/hungergames/IHungerGamesScreensOpener.java"));
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the opener", unreadable);
        }

        List<String> names = screens().stream().map(Screen::name).toList();
        List<String> dead = new ArrayList<>();
        // The five doors, and the screen each is expected to open. A door with nothing behind it is a
        // command that answers with a stack trace.
        record Door(String method, String screen) {
        }
        for (Door door : List.of(
                new Door("admin(", "AdminMenu"),
                new Door("teams(", "TeamsMenu"),
                new Door("shop(", "ShopMenu"),
                new Door("spectate(", "SpectateMenu"),
                new Door("borderConflict(", "BorderConflictMenu"))) {
            if (opener.contains(door.method()) && !names.contains(door.screen())) {
                dead.add(door.method() + " has no " + door.screen());
            }
        }
        assertThat(dead)
                .as("the opener is what a command — built during bootstrap, long before a menu could "
                        + "exist — names instead of naming a menu class. A door with nothing behind it "
                        + "is a command that answers with a stack trace in front of everybody")
                .isEmpty();
    }

    @Test
    @DisplayName("something a gamemaster cannot do right now is greyed out with the reason, not hidden")
    void nothingJustDisappears() {
        // Core has band(band, column, allowed, item, reason, handler) for exactly this: the button stays
        // where it was, greyed, carrying why. The module used it nowhere and hid buttons behind an if in
        // twelve places instead.
        //
        // A missing button cannot be explained. A gamemaster looking for "Start the deathmatch" and not
        // finding it does not learn that the round has not started yet — they learn that the page is
        // different from the one they remember, and the next thing they do is ask whether the plugin is
        // broken. It also moves everything after it, so the button somebody was reaching for is now
        // somewhere else under their cursor.
        List<String> hiding = new ArrayList<>();
        for (Screen screen : screens()) {
            // Line by line rather than one big pattern. The pattern version passed on everything, because
            // [^)]* cannot span the brackets in "if (mayStartOne())" — it stopped at the first ) and matched
            // nothing. A source scan that silently matches nothing is the worst kind of green.
            List<String> lines = screen.body().lines().toList();
            boolean saysWhyNot = screen.body().contains("Icons.locked(");
            for (int i = 0; i < lines.size() - 1; i++) {
                String line = lines.get(i).strip();
                if (!line.startsWith("if (") || !line.endsWith(") {")) {
                    continue;
                }
                boolean aboutPermissionOrPhase = line.contains("may") || line.contains("can")
                        || line.contains("allowed") || line.contains("isFrozen")
                        || line.contains("enabled") || line.contains("isRunning");
                if (!aboutPermissionOrPhase) {
                    continue;
                }
                String next = nextCodeLine(lines, i + 1);
                boolean drawsAButton = next.startsWith("toolbar(") || next.startsWith("tile(")
                        || next.startsWith("set(") || next.startsWith("band(")
                        || next.startsWith("danger(");
                if (drawsAButton && !saysWhyNot) {
                    hiding.add(screen.name() + ": " + line);
                }
            }
        }
        assertThat(hiding)
                .as("these decide whether a button exists rather than whether it works. Use Core's "
                        + "band(..., allowed, reason, ...) or Icons.locked so it stays put and says why")
                .isEmpty();
    }
}
