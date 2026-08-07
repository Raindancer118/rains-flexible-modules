package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may open a team's own identity page from the player-facing {@code TeamsMenu} — the fix for
 * "I have no way to pick my team's colour, and the config option for that does nothing".
 *
 * <p>{@code TeamRules.playersCanChooseColor} existed and reached {@code TeamPolicy}, which itself never
 * enforces it — see {@code Teams.setColour}, which only checks {@code exclusiveColours}. Nothing in this
 * module ever asked the flag a second question, so it was a setting nobody could turn on and see anything
 * happen. {@link TeamsMenu#mayCustomize} is the one place that now reads it.
 */
class TeamsMenuTest {

    private static final UUID CAPTAIN = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private static TeamRules rules(boolean playersCanChooseColor, boolean captainEnabled) {
        return new TeamRules(0, 0, true, captainEnabled, true, playersCanChooseColor, GamePhase.STARTUP);
    }

    private static Team teamWithCaptain() {
        return Team.of(TeamId.fromName("red"), "Red", TeamColour.RED)
                .withMembers(Set.of(CAPTAIN, MEMBER))
                .withCaptain(Optional.of(CAPTAIN));
    }

    private static Team teamWithoutCaptain() {
        return Team.of(TeamId.fromName("red"), "Red", TeamColour.RED).withMembers(Set.of(MEMBER));
    }

    @Nested
    @DisplayName("the master switch")
    class TurnedOff {

        @Test
        @DisplayName("nobody may customize when the server has it off, not even the captain")
        void refusesEverybody() {
            assertThat(TeamsMenu.mayCustomize(rules(false, true), teamWithCaptain(), CAPTAIN)).isFalse();
        }
    }

    @Nested
    @DisplayName("with captains enabled")
    class WithCaptains {

        @Test
        @DisplayName("the captain may customize their own team")
        void captainMay() {
            assertThat(TeamsMenu.mayCustomize(rules(true, true), teamWithCaptain(), CAPTAIN)).isTrue();
        }

        @Test
        @DisplayName("a plain member may not, once a captain leads the team")
        void memberMayNot() {
            assertThat(TeamsMenu.mayCustomize(rules(true, true), teamWithCaptain(), MEMBER)).isFalse();
        }

        @Test
        @DisplayName("a stranger to the team may not, regardless")
        void strangerMayNot() {
            assertThat(TeamsMenu.mayCustomize(rules(true, true), teamWithCaptain(), STRANGER)).isFalse();
        }

        @Test
        @DisplayName("with nobody captain yet, any of its members may set the team up")
        void noCaptainYetIsAnybodysToSet() {
            assertThat(TeamsMenu.mayCustomize(rules(true, true), teamWithoutCaptain(), MEMBER)).isTrue();
        }
    }

    @Nested
    @DisplayName("with captains disabled")
    class WithoutCaptains {

        @Test
        @DisplayName("any member may customize — there is no leader to defer to")
        void anyMemberMay() {
            assertThat(TeamsMenu.mayCustomize(rules(true, false), teamWithCaptain(), MEMBER)).isTrue();
        }
    }
}
