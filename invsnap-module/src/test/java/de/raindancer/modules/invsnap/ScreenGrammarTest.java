package de.raindancer.modules.invsnap;

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
 * That the screens keep to one grammar — the same rules the claims, homes, warps, moderation and
 * mannequin modules keep to. Repeated deliberately; see {@code mannequin-module}'s own copy for why.
 */
class ScreenGrammarTest {

    private static final Path SCREENS = Path.of("src/main/java/de/raindancer/modules/invsnap/screen");

    private static final List<String> BASE_CLASSES = List.of("IInvSnapScreen");

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
                "ConfirmScreen", "IInvSnapScreen", "SnapshotHistoryMenu");
        assertThat(drawnScreens()).isNotEmpty();
    }

    @Test
    @DisplayName("every screen that reads a right click says so on the button")
    void rightClicksAreAdvertised() {
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
    @DisplayName("every screen has a name for the diagnostic")
    void everyScreenDescribesItself() {
        List<String> silent = new ArrayList<>();
        for (Screen screen : drawnScreens()) {
            if (!screen.body().contains("describe()")) {
                silent.add(screen.name());
            }
        }
        assertThat(silent)
                .as("these never override describe(), so a diagnostic listing screens cannot name them")
                .isEmpty();
    }
}
