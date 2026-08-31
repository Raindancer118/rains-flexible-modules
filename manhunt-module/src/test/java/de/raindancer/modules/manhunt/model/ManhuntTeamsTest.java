package de.raindancer.modules.manhunt.model;

import de.raindancer.core.social.team.TeamOutcome;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ManhuntTeamsTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Test
    void bothTeamsExistFromConstruction() {
        ManhuntTeams teams = new ManhuntTeams(() -> false);

        assertThat(teams.raw().team(ManhuntTeams.RUNNERS)).isPresent();
        assertThat(teams.raw().team(ManhuntTeams.HUNTERS)).isPresent();
        assertThat(teams.runners()).isEmpty();
        assertThat(teams.hunters()).isEmpty();
    }

    @Test
    void joiningPutsSomebodyOnExactlyOneSide() {
        ManhuntTeams teams = new ManhuntTeams(() -> false);

        assertThat(teams.joinRunners(ALICE).status()).isEqualTo(TeamOutcome.SUCCESS);

        assertThat(teams.isRunner(ALICE)).isTrue();
        assertThat(teams.isHunter(ALICE)).isFalse();
        assertThat(teams.everybody()).containsExactly(ALICE);
    }

    @Test
    void switchingSidesMovesThemRatherThanDuplicatingThem() {
        ManhuntTeams teams = new ManhuntTeams(() -> false);
        teams.joinRunners(ALICE);

        de.raindancer.core.social.team.Teams.MembershipChange moved = teams.joinHunters(ALICE);
        assertThat(moved.status()).isEqualTo(TeamOutcome.SUCCESS);
        assertThat(moved.oldTeam()).contains(ManhuntTeams.RUNNERS);

        assertThat(teams.isHunter(ALICE)).isTrue();
        assertThat(teams.isRunner(ALICE)).isFalse();
        assertThat(teams.runners()).isEmpty();
        assertThat(teams.hunters()).containsExactly(ALICE);
    }

    @Test
    void leavingClearsBothSides() {
        ManhuntTeams teams = new ManhuntTeams(() -> false);
        teams.joinHunters(BOB);

        assertThat(teams.leave(BOB)).contains(ManhuntTeams.HUNTERS);
        assertThat(teams.isHunter(BOB)).isFalse();
        assertThat(teams.everybody()).isEmpty();
    }

    @Test
    void rolesCannotChangeWhileFrozen() {
        ManhuntTeams teams = new ManhuntTeams(() -> true);

        assertThat(teams.joinRunners(ALICE).status()).isEqualTo(TeamOutcome.FROZEN);
        assertThat(teams.everybody()).isEmpty();
    }

    @Test
    void bothSidesTogetherAreEverybody() {
        ManhuntTeams teams = new ManhuntTeams(() -> false);
        teams.joinRunners(ALICE);
        teams.joinHunters(BOB);

        assertThat(teams.everybody()).containsExactlyInAnyOrder(ALICE, BOB);
    }
}
