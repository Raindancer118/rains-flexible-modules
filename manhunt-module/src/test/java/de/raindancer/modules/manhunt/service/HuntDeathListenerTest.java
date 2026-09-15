package de.raindancer.modules.manhunt.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a death costs, in a hunt: everything for a Runner, nothing for a Hunter, and the run itself
 * once the last Runner is out.
 */
class HuntDeathListenerTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());
    private static final UUID CARO = UUID.nameUUIDFromBytes("caro".getBytes());

    private Hunt hunt;
    private SpeedrunSession session;
    private Eliminations eliminations;
    private HuntDeathListener listener;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        hunt = Hunt.of(Set.of(ANNA, BEN, CARO), Set.of(ANNA, BEN));
        session = new SpeedrunSession(Set.of(ANNA, BEN, CARO));
        session.start();
        eliminations = mock(Eliminations.class);
        listener = new HuntDeathListener(plugin, hunt, session, eliminations, mock(Messages.class));
    }

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(id.toString().substring(0, 4));
        return player;
    }

    /**
     * Mocked rather than constructed: a real {@link PlayerDeathEvent} needs a {@code DamageSource},
     * and building one reaches for the running server's damage-type registry — the same thing that
     * makes {@code ItemSpec} untestable in this reactor. Only the dead player matters here.
     */
    private static PlayerDeathEvent deathOf(Player player) {
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(player);
        return event;
    }

    @Test
    @DisplayName("a Runner's death takes them out of the hunt")
    void runnerIsEliminated() {
        listener.onDeath(deathOf(playerWithId(ANNA)));

        assertThat(hunt.isEliminated(ANNA)).isTrue();
        assertThat(session.state()).as("one Runner is still going").isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    @DisplayName("a Hunter's death costs the hunt nothing")
    void hunterDeathIsNothing() {
        listener.onDeath(deathOf(playerWithId(CARO)));

        assertThat(hunt.isEliminated(CARO)).isFalse();
        assertThat(session.state()).isEqualTo(SpeedrunState.RUNNING);
    }

    @Test
    @DisplayName("the last Runner's death ends the run, and says who won by the reason it ends with")
    void lastRunnerEndsTheHunt() {
        listener.onDeath(deathOf(playerWithId(ANNA)));
        listener.onDeath(deathOf(playerWithId(BEN)));

        assertThat(session.state()).isEqualTo(SpeedrunState.FINISHED);
        assertThat(session.outcome().orElseThrow().reason())
                .isEqualTo(HuntDeathListener.HUNTERS_WIN);
    }

    @Test
    @DisplayName("a death after the hunt is over changes nothing")
    void deathAfterTheEnd() {
        session.finish("advancement:minecraft:end/kill_dragon");

        listener.onDeath(deathOf(playerWithId(ANNA)));

        assertThat(hunt.isEliminated(ANNA)).isFalse();
        assertThat(session.outcome().orElseThrow().reason())
                .isEqualTo("advancement:minecraft:end/kill_dragon");
    }

    @Test
    @DisplayName("an eliminated Runner respawns as a spectator")
    void respawnsWatching() {
        Player anna = playerWithId(ANNA);
        listener.onDeath(deathOf(anna));

        listener.onRespawn(respawnOf(anna));

        verify(eliminations).spectate(anna);
    }

    @Test
    @DisplayName("a Hunter respawns as themselves")
    void hunterRespawnsPlaying() {
        Player caro = playerWithId(CARO);

        listener.onRespawn(respawnOf(caro));

        verify(eliminations, never()).spectate(any(Player.class));
    }

    @Test
    @DisplayName("the last Runner is not left spectating a hunt their own death ended")
    void lastRunnerPlaysOn() {
        Player anna = playerWithId(ANNA);
        Player ben = playerWithId(BEN);
        listener.onDeath(deathOf(anna));
        listener.onDeath(deathOf(ben));

        listener.onRespawn(respawnOf(ben));

        verify(eliminations, never()).spectate(ben);
    }

    private static PlayerRespawnEvent respawnOf(Player player) {
        PlayerRespawnEvent event = mock(PlayerRespawnEvent.class);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }
}
