package de.raindancer.modules.wallsroads;

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
 * That these screens keep to the same grammar as every other module's.
 *
 * <p>Repeated deliberately, module by module — the grammar exists so that somebody who has learnt
 * one of this server's menus has learnt all of them, and a rule checked in one module and merely
 * written down in the next is a rule that lasts until the first hurried afternoon.
 */
class ScreenGrammarTest {

    private static final Path SCREENS = Path.of("src/main/java/de/raindancer/modules/wallsroads/screen");

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
                .contains("WallEditMenu", "RoadEditMenu", "GateListMenu", "SignListMenu",
                        "WallsRoadsListMenu", "ConfirmScreen");
    }

    @Test
    @DisplayName("every screen that reads a right click says so on the button")
    void rightClicksAreAdvertised() {
        List<String> silent = new ArrayList<>();
        for (Screen screen : screens()) {
            if (screen.body().contains("isRightClick()")
                    && !screen.body().toLowerCase().contains("right click")) {
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
                .as("the danger slot is flanked by navigation, so a misclick must cost a page rather "
                        + "than the thing itself")
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
                .as("these build item stacks by hand instead of using Icons, which is how a server "
                        + "ends up with two ideas of what a button looks like")
                .isEmpty();
    }

    @Test
    @DisplayName("a button somebody may not use is greyed by the layout, never left out of it")
    void permissionsReachTheLayout() {
        List<String> hiding = new ArrayList<>();
        for (Screen screen : screens()) {
            if (!screen.body().contains("band(")) {
                continue;
            }
            boolean asksAboutPermission = screen.body().contains("hasPermission(");
            boolean greysThroughLayout = screen.body().contains("mayManage,")
                    || screen.body().contains("Icons.locked(");
            if (asksAboutPermission && !greysThroughLayout) {
                hiding.add(screen.name());
            }
        }
        assertThat(hiding)
                .as("hiding makes the menu a different shape per viewer, so nobody can be told 'the "
                        + "third one along', and 'why can I not see it' has no answer on screen")
                .isEmpty();
    }

    @Test
    @DisplayName("buttons that open a page sit at least two columns apart")
    void buttonsAreNotCrowded() {
        List<String> crowded = new ArrayList<>();
        for (Screen screen : screens()) {
            // Per band: two buttons in the same column of different rows are not next to each other.
            java.util.Map<String, List<Integer>> columnsByBand = new java.util.LinkedHashMap<>();
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("band\\(MenuLayout\\.([A-Z_]+), (\\d+)").matcher(screen.body());
            while (matcher.find()) {
                columnsByBand.computeIfAbsent(matcher.group(1), band -> new ArrayList<>())
                        .add(Integer.parseInt(matcher.group(2)));
            }
            for (List<Integer> columns : columnsByBand.values()) {
                java.util.Collections.sort(columns);
                for (int i = 1; i < columns.size(); i++) {
                    if (columns.get(i) - columns.get(i - 1) == 1) {
                        crowded.add(screen.name());
                        break;
                    }
                }
            }
        }
        assertThat(crowded).as("a wall of adjacent buttons is unreadable — leave a pane between them")
                .isEmpty();
    }
}
