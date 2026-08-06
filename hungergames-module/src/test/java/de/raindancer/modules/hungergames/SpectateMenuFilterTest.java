package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.screen.SpectateMenu;
import de.raindancer.modules.hungergames.store.GameSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SpectateMenu#onlineLivingTributes} is the filter that decides who a spectator's teleport list
 * actually offers. A teleport target that turns out to be eliminated or offline is not a refusal a screen
 * should ever have to explain — it should simply never have been a button, which is what this pulls out
 * as pure set arithmetic to check without a server.
 */
class SpectateMenuFilterTest {

    private GameSession session;
    private final UUID alive1 = UUID.randomUUID();
    private final UUID alive2 = UUID.randomUUID();
    private final UUID eliminated = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), () -> 1_000_000L, new Random(3));
        session.whitelistAdd(alive1, "Amy");
        session.whitelistAdd(alive2, "Bo");
        session.whitelistAdd(eliminated, "Cass");
        session.transitionTo(de.raindancer.modules.hungergames.model.GamePhase.PREFLIGHT);
        session.transitionTo(de.raindancer.modules.hungergames.model.GamePhase.LOBBY);
        session.transitionTo(de.raindancer.modules.hungergames.model.GamePhase.STARTUP);
        session.transitionTo(de.raindancer.modules.hungergames.model.GamePhase.READY);
        session.transitionTo(de.raindancer.modules.hungergames.model.GamePhase.RUNNING);
        session.eliminate(eliminated, null);
    }

    @Test
    @DisplayName("an eliminated tribute never appears, online or not")
    void eliminatedNeverAppears() {
        List<UUID> result = SpectateMenu.onlineLivingTributes(session, uuid -> true);

        assertThat(result).doesNotContain(eliminated);
    }

    @Test
    @DisplayName("a living tribute who is offline is filtered out too")
    void offlineLivingIsFiltered() {
        List<UUID> result = SpectateMenu.onlineLivingTributes(session, Set.of(alive1)::contains);

        assertThat(result).containsExactly(alive1);
    }

    @Test
    @DisplayName("every living, online tribute is offered, sorted for a stable page")
    void livingOnlineAreOffered() {
        List<UUID> result = SpectateMenu.onlineLivingTributes(session, uuid -> true);

        assertThat(result).containsExactlyInAnyOrder(alive1, alive2);
        assertThat(result).isSorted();
    }
}
