package de.raindancer.modules.hungergames;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the admin team page is actually reachable from a running server, not merely built and tested.
 *
 * <h2>The bug this was written for</h2>
 * {@code TeamAdminMenu} (create, delete, recolour, assign a captain) and {@code TeamIdentityMenu} (a single
 * team's colour, emblem and badge) were both fully written. Neither was ever constructed by
 * {@code HungerGamesWiring} or {@code AdminMenu} — no tile, no command, nothing. A gamemaster had no way at
 * all to manage teams beyond {@code /hg team assign}, and no way in-game to change {@code teams.max-size}
 * or any other team setting short of hand-editing {@code config.yml} and restarting.
 *
 * <p>{@code TeamsMenu}'s own class note even said "an admin can still recolour a team from
 * {@code TeamAdminMenu} once it exists" — a forward reference nobody ever closed.
 *
 * <p>A source scan, the same shape as {@code EveryItemIsRegisteredTest} and
 * {@code TheHttpApiActuallyStartsTest}, for the same reason: a page that can be built in a test proves the
 * page works, not that a real server ever shows it to anybody.
 */
class TheTeamAdminPageIsActuallyReachableTest {

    private static String adminMenuSource() {
        try {
            return Files.readString(Path.of(
                    "src/main/java/de/raindancer/modules/hungergames/screen/AdminMenu.java"));
        } catch (IOException unreadable) {
            throw new AssertionError("could not read AdminMenu.java", unreadable);
        }
    }

    @Test
    @DisplayName("the scan reads the real screen, so it cannot pass by reading nothing")
    void theScanIsNotVacuous() {
        assertThat(adminMenuSource()).contains("protected void render()");
    }

    @Test
    @DisplayName("AdminMenu actually constructs TeamAdminMenu")
    void constructed() {
        assertThat(adminMenuSource())
                .as("built and unit tested is not the same thing as a gamemaster ever seeing this page — "
                        + "nothing before this called new TeamAdminMenu(...) from anywhere real")
                .contains("new TeamAdminMenu(");
    }
}
