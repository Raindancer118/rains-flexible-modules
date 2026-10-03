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

    /** A listener for a hunt played with {@code lives} lives and a respawn wait, reporting to a recorder. */
    private final java.util.List<String> reported = new java.util.ArrayList<>();
    private HunterHoldListener hold;

    private HuntDeathListener withLives(int lives, int respawnWait) {
        hold = new HunterHoldListener(hunt, () -> 0L);
        hold.release();
        HuntWatcher watcher = new HuntWatcher() {
            @Override
            public void died(Hunt h, UUID who, String name, UUID by, String byName, Death death, int livesLeft) {
                reported.add(death + ":" + name + ":" + byName + ":" + livesLeft);
            }
        };
        return new HuntDeathListener(mock(Plugin.class, org.mockito.Answers.RETURNS_DEEP_STUBS), hunt, session,
                eliminations, messages, () -> lives, hold, () -> respawnWait, watcher);
    }

    private final Messages messages = mock(Messages.class);

    @Test
    @DisplayName("with two lives, a Runner's first death costs a life and the second catches them")
    void lives() {
        HuntDeathListener twoLives = withLives(2, 0);
        Player anna = playerWithId(ANNA);
        Player caro = playerWithId(CARO);
        when(anna.getKiller()).thenReturn(caro);

        twoLives.onDeath(deathOf(anna));
        assertThat(hunt.isEliminated(ANNA)).isFalse();
        twoLives.onRespawn(respawnOf(anna));
        verify(eliminations, never()).spectate(anna);

        twoLives.onDeath(deathOf(anna));
        assertThat(hunt.isEliminated(ANNA)).isTrue();
        assertThat(reported).containsExactly(
                "LIFE_LOST:" + anna.getName() + ":" + caro.getName() + ":1",
                "CAUGHT:" + anna.getName() + ":" + caro.getName() + ":0");
    }

    @Test
    @DisplayName("a Hunter's death is reported, killer and all, and costs nothing")
    void hunterDeathReported() {
        HuntDeathListener listening = withLives(1, 0);
        Player caro = playerWithId(CARO);
        Player anna = playerWithId(ANNA);
        when(caro.getKiller()).thenReturn(anna);

        listening.onDeath(deathOf(caro));

        assertThat(reported).containsExactly("HUNTER_DIED:" + caro.getName() + ":" + anna.getName() + ":0");
    }

    @Test
    @DisplayName("a Hunter back from dying waits out the respawn delay before rejoining")
    void respawnWait() {
        HuntDeathListener waiting = withLives(1, 5);
        Player caro = playerWithId(CARO);

        waiting.onRespawn(respawnOf(caro));

        assertThat(hold.isHeld(CARO)).isTrue();
        assertThat(hold.isHeld(ANNA)).as("a Runner never waits").isFalse();
    }

    @Test
    @DisplayName("no respawn delay, no wait")
    void noRespawnWait() {
        HuntDeathListener waiting = withLives(1, 0);

        waiting.onRespawn(respawnOf(playerWithId(CARO)));

        assertThat(hold.isHeld(CARO)).isFalse();
    }
}
