package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HungerGamesWiring#shouldResetForRoundStart} — the fix for the phase watcher's mass inventory
 * clear at the start of a round, which used to key only on whether a player was whitelisted and never on
 * where they actually were. A whitelisted tribute who stepped away to another world before the countdown
 * finished — the farm world, say — came back at {@code RUNNING} to find that inventory emptied too, on a
 * server this module never touched.
 */
class HungerGamesWiringPhaseWatcherTest {

    private GameSession session;
    private final World arenaWorld = mock(World.class);
    private final World otherWorld = mock(World.class);
    private final UUID tributeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), () -> 0L, new Random(1));
        session.whitelistAdd(tributeId, "Tribute");
    }

    private Player playerIn(World world) {
        Player player = mock(Player.class);
        lenient().when(player.getUniqueId()).thenReturn(tributeId);
        lenient().when(player.getWorld()).thenReturn(world);
        return player;
    }

    @Test
    void aWhitelistedTributeInTheArenaWorldIsReset() {
        assertThat(HungerGamesWiring.shouldResetForRoundStart(session, playerIn(arenaWorld), arenaWorld))
                .isTrue();
    }

    @Test
    void aWhitelistedTributeInAnUnrelatedWorldIsLeftAlone() {
        assertThat(HungerGamesWiring.shouldResetForRoundStart(session, playerIn(otherWorld), arenaWorld))
                .isFalse();
    }

    @Test
    void aStrangerInTheArenaWorldIsNotResetEither() {
        Player stranger = mock(Player.class);
        when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(stranger.getWorld()).thenReturn(arenaWorld);

        assertThat(HungerGamesWiring.shouldResetForRoundStart(session, stranger, arenaWorld)).isFalse();
    }
}
