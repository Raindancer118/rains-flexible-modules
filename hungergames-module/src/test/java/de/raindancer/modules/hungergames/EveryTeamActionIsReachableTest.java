package de.raindancer.modules.hungergames;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That everything the session can do to a team can be done from a screen.
 *
 * <h2>Why this is worth a test of its own</h2>
 * {@code GameSession} grew a method per team operation, and a screen reached most of them. The ones nothing
 * reached were invisible: {@code teamSetColour} was called only by the HTTP API for weeks, so recolouring a
 * team meant a REST request. {@code teamSetCaptain} and admin-side {@code teamAssign} were the same — real,
 * tested, and unreachable by anybody actually running a tournament.
 *
 * <p>An unreachable method does not fail. It compiles, it has tests, and the only symptom is a gamemaster
 * asking how to do something the plugin can plainly do. So this asserts the surface rather than the
 * behaviour: every {@code team…} operation on the session is named by at least one screen.
 *
 * <p>Deliberately a source scan of the screen package, and deliberately not clever. It cannot tell whether
 * the button is reachable in practice or whether its handler is wired — {@code ScreenGrammarTest} covers dead
 * buttons. What it catches is the operation nobody thought about at all.
 */
class EveryTeamActionIsReachableTest {

    private static final Path SESSION =
            Path.of("src/main/java/de/raindancer/modules/hungergames/store/GameSession.java");
    private static final Path SCREENS =
            Path.of("src/main/java/de/raindancer/modules/hungergames/screen");

    /**
     * The operations that are deliberately not on a screen, and why.
     *
     * <p>Written out rather than inferred, so adding one is a decision somebody makes in this file with a
     * reason attached — which is the whole difference between an exemption and an oversight.
     */
    private static final Map<String, String> NOT_ON_A_SCREEN = Map.of(
            "teamAssignRandomly",
            "reached from TeamAdminMenu's toolbar as 'Assign teamless randomly' — named differently there",
            "teamRemovePlayer",
            "reached by clicking a member on the team's own page, which does not name the method");

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    /** Every {@code public TeamOutcome team…} or {@code public … team…} operation the session offers. */
    private static List<String> teamOperations() {
        List<String> found = new ArrayList<>();
        for (String line : read(SESSION).lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("public ")) {
                continue;
            }
            int bracket = trimmed.indexOf('(');
            if (bracket < 0) {
                continue;
            }
            String[] words = trimmed.substring(0, bracket).split("\\s+");
            String name = words[words.length - 1];
            if (name.startsWith("team") && !name.equals("teams")) {
                found.add(name);
            }
        }
        return found;
    }

    private static String everyScreen() {
        try (Stream<Path> files = Files.walk(SCREENS)) {
            StringBuilder all = new StringBuilder();
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                all.append(read(file));
            }
            return all.toString();
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the screens", unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the session's team operations, so it cannot pass on an empty list")
    void theScanIsNotVacuous() {
        assertThat(teamOperations())
                .as("GameSession has a method per team operation; finding none would make the rule vacuous")
                .hasSizeGreaterThanOrEqualTo(6)
                .contains("teamCreate", "teamSetColour");
    }

    @Test
    @DisplayName("every team operation is reachable from a screen")
    void nothingIsApiOnly() {
        String screens = everyScreen();
        List<String> unreachable = new ArrayList<>();

        for (String operation : teamOperations()) {
            if (NOT_ON_A_SCREEN.containsKey(operation)) {
                continue;
            }
            if (!screens.contains(operation)) {
                unreachable.add(operation);
            }
        }
        assertThat(unreachable)
                .as("these can be done by the HTTP API and by nothing a gamemaster can click. An unreachable "
                        + "method does not fail — the only symptom is somebody asking how to do a thing the "
                        + "plugin plainly does")
                .isEmpty();
    }

    @Test
    @DisplayName("the exemptions name operations that really exist")
    void theExemptionsAreNotStale() {
        // An exemption for a method that has been renamed is an exemption quietly covering nothing, and the
        // next unreachable operation slips in behind it.
        assertThat(teamOperations()).containsAll(NOT_ON_A_SCREEN.keySet());
    }
}
