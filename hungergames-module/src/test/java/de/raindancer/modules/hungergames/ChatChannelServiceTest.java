package de.raindancer.modules.hungergames;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.modules.hungergames.model.ChatChannel;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.ChatChannelService;
import de.raindancer.modules.hungergames.service.ChatChannelService.SwitchOutcome;
import de.raindancer.modules.hungergames.store.GameEvents.MembershipCause;
import de.raindancer.modules.hungergames.store.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of a tribute's channels their chat actually goes to — the fix for there being no channel at all:
 * every tribute's chat was ordinary server chat, heard by everybody including whoever had just been
 * eliminated.
 */
class ChatChannelServiceTest {

    private GameSession session;
    private ChatChannelService service;

    private final UUID tribute = UUID.randomUUID();
    private final UUID teammate = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), () -> 0L, new Random(1));
        session.whitelistAdd(tribute, "Tribute");
        session.whitelistAdd(teammate, "Teammate");
        service = new ChatChannelService(session);
    }

    @Nested
    @DisplayName("the default, before anybody has switched")
    class Defaults {

        @Test
        @DisplayName("a fresh tribute talks to everybody")
        void startsOnAll() {
            assertThat(service.effectiveChannel(tribute)).isEqualTo(ChatChannel.ALL);
        }
    }

    @Nested
    @DisplayName("switching")
    class Switching {

        @Test
        @DisplayName("refuses TEAM for a tribute with no team")
        void refusesTeamWithNoTeam() {
            assertThat(service.switchTo(tribute, ChatChannel.TEAM)).isEqualTo(SwitchOutcome.YOU_HAVE_NO_TEAM);
            assertThat(service.effectiveChannel(tribute)).isEqualTo(ChatChannel.ALL);
        }

        @Test
        @DisplayName("a tribute on a team can switch to TEAM and back to ALL")
        void switchesBackAndForth() {
            var team = session.teamCreate("Careers", TeamColour.RED).team().orElseThrow();
            session.teamAssign(tribute, team.id(), MembershipCause.API);

            assertThat(service.switchTo(tribute, ChatChannel.TEAM)).isEqualTo(SwitchOutcome.OK);
            assertThat(service.effectiveChannel(tribute)).isEqualTo(ChatChannel.TEAM);

            assertThat(service.switchTo(tribute, ChatChannel.ALL)).isEqualTo(SwitchOutcome.OK);
            assertThat(service.effectiveChannel(tribute)).isEqualTo(ChatChannel.ALL);
        }

        @Test
        @DisplayName("refuses SPECTATOR outright — nobody chooses their way into it")
        void refusesSpectatorAsAChoice() {
            assertThat(service.switchTo(tribute, ChatChannel.SPECTATOR))
                    .isEqualTo(SwitchOutcome.YOU_ARE_A_SPECTATOR);
        }

        @Test
        @DisplayName("other() flips TEAM to ALL and ALL to TEAM")
        void otherFlips() {
            var team = session.teamCreate("Careers", TeamColour.RED).team().orElseThrow();
            session.teamAssign(tribute, team.id(), MembershipCause.API);

            assertThat(service.other(tribute)).isEqualTo(ChatChannel.TEAM);
            service.switchTo(tribute, ChatChannel.TEAM);
            assertThat(service.other(tribute)).isEqualTo(ChatChannel.ALL);
        }
    }

    @Nested
    @DisplayName("being eliminated")
    class Eliminated {

        private void runARoundAndEliminate(UUID victim) {
            session.transitionTo(GamePhase.PREFLIGHT);
            session.transitionTo(GamePhase.LOBBY);
            session.transitionTo(GamePhase.STARTUP);
            session.transitionTo(GamePhase.READY);
            session.transitionTo(GamePhase.RUNNING);
            session.eliminate(victim, null);
        }

        @Test
        @DisplayName("overrides whatever was chosen, without erasing it")
        void overridesTheChoice() {
            var team = session.teamCreate("Careers", TeamColour.RED).team().orElseThrow();
            session.teamAssign(tribute, team.id(), MembershipCause.API);
            session.teamAssign(teammate, team.id(), MembershipCause.API);
            service.switchTo(tribute, ChatChannel.TEAM);

            runARoundAndEliminate(tribute);

            assertThat(service.effectiveChannel(tribute)).isEqualTo(ChatChannel.SPECTATOR);
        }

        @Test
        @DisplayName("cannot be switched away from while eliminated")
        void cannotSwitchWhileEliminated() {
            runARoundAndEliminate(tribute);

            assertThat(service.switchTo(tribute, ChatChannel.ALL)).isEqualTo(SwitchOutcome.YOU_ARE_A_SPECTATOR);
        }
    }

    @Test
    @DisplayName("forget() drops a remembered choice back to the default")
    void forgetDropsThePreference() {
        var team = session.teamCreate("Careers", TeamColour.RED).team().orElseThrow();
        session.teamAssign(tribute, team.id(), MembershipCause.API);
        service.switchTo(tribute, ChatChannel.TEAM);

        service.forget(tribute);

        assertThat(service.effectiveChannel(tribute)).isEqualTo(ChatChannel.ALL);
    }

    @Test
    @DisplayName("what it calls itself, for the console line listing what started")
    void itSaysWhatItIs() {
        assertThat(service.describe()).contains("channel");
    }
}
