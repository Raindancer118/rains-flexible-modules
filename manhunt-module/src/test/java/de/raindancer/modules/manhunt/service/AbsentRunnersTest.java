package de.raindancer.modules.manhunt.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A Runner who logs out and stays away — the hunt must not wait for them forever. */
@DisplayName("a Runner away too long")
class AbsentRunnersTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());
    private static final UUID CARO = UUID.nameUUIDFromBytes("caro".getBytes());

    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final List<Long> delays = new ArrayList<>();
    private final List<Runnable> timers = new ArrayList<>();
    private final AtomicInteger grace = new AtomicInteger(300);
    private final AtomicBoolean stillOn = new AtomicBoolean(true);
    private Hunt hunt;
    private SpeedrunSession session;
    private AbsentRunners absent;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOfflinePlayer(any(UUID.class))).thenReturn(mock(org.bukkit.OfflinePlayer.class));
        hunt = Hunt.of(Set.of(ANNA, BEN, CARO), Set.of(ANNA, BEN));
        session = new SpeedrunSession(Set.of(ANNA, BEN, CARO));
        session.start();
        absent = new AbsentRunners(plugin, hunt, session, stillOn::get, grace::get,
                (ticks, task) -> {
                    delays.add(ticks);
                    timers.add(task);
                }, messages);
    }

    private Player online(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(id.toString().substring(0, 4));
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    private void runTimers() {
        List<Runnable> due = List.copyOf(timers);
        timers.clear();
        due.forEach(Runnable::run);
    }

    @Test
    @DisplayName("away past the grace, a Runner is caught — and the others are told")
    void caughtAfterTheGrace() {
        Player caro = online(CARO);

        absent.away(ANNA);
        assertThat(delays).containsExactly(300L * 20);
        runTimers();

        assertThat(hunt.isEliminated(ANNA)).isTrue();
        assertThat(session.state()).as("one Runner is still in it").isEqualTo(SpeedrunState.RUNNING);
        verify(messages).send(eq(caro), eq("manhunt.caught-away"), any(Object[].class));
    }

    @Test
    @DisplayName("the last Runner away too long ends the hunt as the Hunters' win")
    void lastRunnerAwayEndsIt() {
        hunt.eliminate(BEN);

        absent.away(ANNA);
        runTimers();

        assertThat(session.outcome()).hasValueSatisfying(outcome ->
                assertThat(outcome.reason()).isEqualTo(HuntDeathListener.HUNTERS_WIN));
    }

    @Test
    @DisplayName("coming back in time costs nothing")
    void backInTime() {
        absent.away(ANNA);
        online(ANNA);
        absent.back(ANNA);
        runTimers();

        assertThat(hunt.isEliminated(ANNA)).isFalse();
    }

    @Test
    @DisplayName("away, back, away again: the first absence's timer cannot catch them early")
    void secondAbsenceStartsAgain() {
        absent.away(ANNA);
        absent.back(ANNA);
        absent.away(ANNA);
        Runnable first = timers.getFirst();

        first.run();
        assertThat(hunt.isEliminated(ANNA)).isFalse();

        timers.getLast().run();
        assertThat(hunt.isEliminated(ANNA)).isTrue();
    }

    @Test
    @DisplayName("a timer that finds them online after all catches nobody")
    void onlineWhenTheTimerFires() {
        absent.away(ANNA);
        online(ANNA);
        runTimers();

        assertThat(hunt.isEliminated(ANNA)).isFalse();
    }

    @Test
    @DisplayName("with the grace at 0, nobody is ever caught for being away")
    void zeroIsNever() {
        grace.set(0);

        absent.away(ANNA);

        assertThat(timers).isEmpty();
    }

    @Test
    @DisplayName("a Hunter or a Runner already caught may stay away as long as they like")
    void onlyLivingRunners() {
        hunt.eliminate(BEN);

        absent.away(CARO);
        absent.away(BEN);

        assertThat(timers).isEmpty();
    }

    @Test
    @DisplayName("a hunt over before the grace runs out catches nobody afterwards")
    void huntOverFirst() {
        absent.away(ANNA);
        stillOn.set(false);
        runTimers();

        assertThat(hunt.isEliminated(ANNA)).isFalse();
    }

    @Test
    @DisplayName("a finished run catches nobody either — the same tick as a goal, say")
    void finishedFirst() {
        absent.away(ANNA);
        session.finish("advancement:minecraft:end/kill_dragon");
        runTimers();

        assertThat(hunt.isEliminated(ANNA)).isFalse();
        assertThat(session.outcome().orElseThrow().reason()).startsWith("advancement:");
    }
}
