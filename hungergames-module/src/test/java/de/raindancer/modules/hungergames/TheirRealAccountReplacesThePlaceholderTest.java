package de.raindancer.modules.hungergames;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.modules.hungergames.model.GameClock;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.TributeRoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tribute added by name, before ever joining, becomes their real account the moment they connect.
 *
 * <h2>The bug, reported from a live server</h2>
 * A tribute's head in the tribute chooser was always Alex's — the default skin — even for a real player who
 * had joined and was standing right there. The cause was upstream of the icon: {@code /allow} and the
 * tribute file both register somebody who has never connected under a UUID derived from their name
 * ({@link TributeRoster#derivedIdFor}), because there is nobody online yet to ask for a real one.
 * {@code AllowCommand}'s own javadoc claimed a join would replace it — "the registry keys on whoever
 * actually connects" — and nothing anywhere did that. {@code Icons.head} was asking Mojang for a skin
 * belonging to a UUID no Minecraft account has, and getting the fallback.
 *
 * <p>The icon was the visible half. The real half is that every screen, count and the winner check itself
 * went on reading the placeholder forever: a tribute who joined, played the whole round and won would still
 * be filed under a UUID that was never their account.
 */
class TheirRealAccountReplacesThePlaceholderTest {

    private GameSession session;

    private void newSession() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), GameClock.system(), new Random(0));
    }

    @Test
    @DisplayName("joining exchanges the placeholder for the real UUID")
    void theSwap() {
        newSession();
        UUID placeholder = TributeRoster.derivedIdFor("Katniss");
        UUID real = UUID.randomUUID();
        session.whitelistAdd(placeholder, "Katniss");

        assertThat(session.claimRealIdentity(placeholder, real, "Katniss")).isTrue();

        assertThat(session.isWhitelisted(real))
                .as("the person actually playing has to be found under the UUID they actually joined with")
                .isTrue();
        assertThat(session.isWhitelisted(placeholder))
                .as("a UUID nobody's account has must not go on meaning somebody")
                .isFalse();
    }

    @Test
    @DisplayName("their alive state survives the swap")
    void stateSurvives() {
        newSession();
        UUID placeholder = TributeRoster.derivedIdFor("Katniss");
        UUID real = UUID.randomUUID();
        session.whitelistAdd(placeholder, "Katniss");

        session.claimRealIdentity(placeholder, real, "Katniss");

        assertThat(session.participants().isAlive(real)).isTrue();
        assertThat(session.participants().all()).hasSize(1);
    }

    @Test
    @DisplayName("their team seat survives the swap")
    void teamSurvives() {
        // Captaincy is not exercised here: a tournament's own policy (TeamRules.defaults()) carries no
        // captains at all, so setCaptain would be refused before there was anything to move. Teams.reassign
        // moving a captaincy along with membership is Core's own promise and is pinned in RainsCore's
        // TeamsTest.Reassignment, against a policy that actually has captains.
        newSession();
        UUID placeholder = TributeRoster.derivedIdFor("Katniss");
        UUID real = UUID.randomUUID();
        session.whitelistAdd(placeholder, "Katniss");
        var created = session.teams().create("District 12", TeamColour.RED);
        session.teams().join(placeholder, created.team().orElseThrow().id());

        session.claimRealIdentity(placeholder, real, "Katniss");

        assertThat(session.teams().teamIdOf(real)).contains(created.team().orElseThrow().id());
        assertThat(session.teams().teamIdOf(placeholder))
                .as("the placeholder must not still hold the seat too — that would be two members for one "
                        + "person")
                .isEmpty();
    }

    @Test
    @DisplayName("somebody who was never added by name is left completely alone")
    void nobodyToClaim() {
        newSession();
        UUID real = UUID.randomUUID();

        assertThat(session.claimRealIdentity(TributeRoster.derivedIdFor("NeverAdded"), real, "NeverAdded"))
                .isFalse();
        assertThat(session.isWhitelisted(real)).isFalse();
    }

    @Test
    @DisplayName("a tribute who already joined once is not disturbed by joining again")
    void alreadyReal() {
        newSession();
        UUID real = UUID.randomUUID();
        session.whitelistAdd(real, "Katniss");

        // The second join finds the placeholder derived from the name, which is not the entry's key any
        // more — this must be a no-op, not a merge of the real entry into a fresh placeholder-keyed one.
        boolean claimed = session.claimRealIdentity(TributeRoster.derivedIdFor("Katniss"), real, "Katniss");

        assertThat(claimed).isFalse();
        assertThat(session.isWhitelisted(real)).isTrue();
        assertThat(session.participants().all()).hasSize(1);
    }

    @Test
    @DisplayName("the real UUID never overwrites an entry that is somehow already there")
    void neverOverwrites() {
        newSession();
        UUID placeholder = TributeRoster.derivedIdFor("Katniss");
        UUID real = UUID.randomUUID();
        session.whitelistAdd(placeholder, "Katniss");
        session.whitelistAdd(real, "SomebodyElseEntirely");

        assertThat(session.claimRealIdentity(placeholder, real, "Katniss"))
                .as("real already names a tribute; claiming into it would silently merge two people")
                .isFalse();
        assertThat(session.participants().nameOf(real)).contains("SomebodyElseEntirely");
        assertThat(session.isWhitelisted(placeholder)).isTrue();
    }
}
