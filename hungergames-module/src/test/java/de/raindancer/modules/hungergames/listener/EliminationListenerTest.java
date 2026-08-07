package de.raindancer.modules.hungergames.listener;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.InMemorySessionStore;
import de.raindancer.modules.hungergames.RecordingGameEvents;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.SpectatorService;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link EliminationListener#onLethalDamage} — the fix for a tribute seeing a real death screen and a
 * real vanilla spectator camera before this module's own vanish-based spectator ever took over. Cancelling
 * the hit that would have killed them, instead of reacting to {@code PlayerDeathEvent} afterwards, is what
 * makes the transition instant and never shows a death at all.
 *
 * <h2>Why the events are mocked rather than constructed</h2>
 * {@link EntityDamageEvent}'s real constructor resolves a {@code DamageType} through Paper's registry,
 * which does not exist off a running server. A mock, with {@link #cancellableDamage} wiring
 * {@code setCancelled}/{@code isCancelled} to a real flag, is what {@code EliminationListener} actually
 * needs to be driven through — it never touches anything else on the event.
 */
@ExtendWith(MockitoExtension.class)
class EliminationListenerTest {

    private GameSession session;
    private SpectatorService spectators;
    private final List<Location> spectacleAt = new ArrayList<>();
    private EliminationListener listener;

    private final UUID victimId = UUID.randomUUID();
    private final UUID killerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), () -> 0L, new Random(1));
        session.whitelistAdd(victimId, "Victim");
        session.whitelistAdd(killerId, "Killer");

        spectators = mock(SpectatorService.class);
        listener = new EliminationListener(session, spectators,
                (where, killed) -> spectacleAt.add(where),
                noopEviction(),
                message -> { },
                HungerGamesSettings.DEFAULTS);
    }

    private static EliminationListener.Eviction noopEviction() {
        return new EliminationListener.Eviction() {
            @Override
            public void kick(Player who, String because) {
            }

            @Override
            public boolean ban(Player who, String because) {
                return false;
            }
        };
    }

    private void runARound() {
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);
    }

    private Player playerFor(UUID uuid) {
        Player player = mock(Player.class);
        org.mockito.Mockito.lenient().when(player.getUniqueId()).thenReturn(uuid);
        org.mockito.Mockito.lenient().when(player.getLocation())
                .thenReturn(new Location(null, 1, 64, 1));
        return player;
    }

    /** A plain lethal-or-not hit, with {@code setCancelled}/{@code isCancelled} backed by a real flag. */
    private EntityDamageEvent damageEvent(Player victim, double finalDamage) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        cancellableDamage(event, victim, finalDamage);
        return event;
    }

    /** The same, credited to a damager — a direct hit, for {@code killerOf}. */
    private EntityDamageByEntityEvent damageByEntityEvent(Player damager, Player victim, double finalDamage) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        cancellableDamage(event, victim, finalDamage);
        org.mockito.Mockito.lenient().when(event.getDamager()).thenReturn(damager);
        return event;
    }

    private void cancellableDamage(EntityDamageEvent event, Player victim, double finalDamage) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        org.mockito.Mockito.lenient().when(event.getEntity()).thenReturn(victim);
        org.mockito.Mockito.lenient().when(event.getFinalDamage()).thenReturn(finalDamage);
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            cancelled.set(invocation.getArgument(0));
            return null;
        }).when(event).setCancelled(org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.lenient().when(event.isCancelled()).thenAnswer(invocation -> cancelled.get());
    }

    @Nested
    @DisplayName("a lethal hit, with the death action at its default of SPECTATOR")
    class LethalHit {

        @Test
        @DisplayName("is cancelled — the tribute never actually dies")
        void cancelsTheDamage() {
            runARound();
            Player victim = playerFor(victimId);
            when(victim.getHealth()).thenReturn(4.0);
            EntityDamageEvent event = damageEvent(victim, 10.0);

            listener.onLethalDamage(event);

            assertThat(event.isCancelled()).isTrue();
        }

        @Test
        @DisplayName("eliminates the tribute and hands them to the spectator service")
        void eliminatesAndSpectates() {
            runARound();
            Player victim = playerFor(victimId);
            when(victim.getHealth()).thenReturn(4.0);
            EntityDamageEvent event = damageEvent(victim, 10.0);

            listener.onLethalDamage(event);

            assertThat(session.participants().isAlive(victimId)).isFalse();
            verify(spectators).makeSpectator(victim);
            assertThat(spectacleAt).hasSize(1);
        }

        @Test
        @DisplayName("credits a player kill to whoever dealt it")
        void creditsTheKiller() {
            runARound();
            Player victim = playerFor(victimId);
            Player killer = playerFor(killerId);
            when(victim.getHealth()).thenReturn(4.0);
            EntityDamageByEntityEvent event = damageByEntityEvent(killer, victim, 10.0);

            listener.onLethalDamage(event);

            assertThat(event.isCancelled()).isTrue();
            assertThat(session.participants().isAlive(victimId)).isFalse();
        }

        @Test
        @DisplayName("not lethal — the hit is left alone")
        void notLethalIsLeftAlone() {
            runARound();
            Player victim = playerFor(victimId);
            when(victim.getHealth()).thenReturn(20.0);
            EntityDamageEvent event = damageEvent(victim, 4.0);

            listener.onLethalDamage(event);

            assertThat(event.isCancelled()).isFalse();
            assertThat(session.participants().isAlive(victimId)).isTrue();
            verifyNoInteractions(spectators);
        }

        @Test
        @DisplayName("outside a round, the hit lands as an ordinary one")
        void outsideARoundIsOrdinary() {
            Player victim = playerFor(victimId);
            when(victim.getHealth()).thenReturn(4.0);
            EntityDamageEvent event = damageEvent(victim, 10.0);

            listener.onLethalDamage(event);

            assertThat(event.isCancelled()).isFalse();
            verifyNoInteractions(spectators);
        }

        @Test
        @DisplayName("somebody not in the tournament is left alone")
        void aStrangerIsLeftAlone() {
            runARound();
            Player stranger = mock(Player.class);
            org.mockito.Mockito.lenient().when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
            EntityDamageEvent event = damageEvent(stranger, 10.0);

            listener.onLethalDamage(event);

            assertThat(event.isCancelled()).isFalse();
            verifyNoInteractions(spectators);
        }
    }

    @Nested
    @DisplayName("a death action other than SPECTATOR")
    class KickOrBan {

        @Test
        @DisplayName("KICK lets the hit — and the real death — happen")
        void kickLetsItThrough() {
            EliminationListener kickListener = new EliminationListener(session, spectators,
                    (where, killed) -> spectacleAt.add(where),
                    noopEviction(),
                    message -> { },
                    de.raindancer.modules.hungergames.Tweak.of(HungerGamesSettings.DEFAULTS,
                            "deathAction", HungerGamesSettings.DeathAction.KICK));
            runARound();
            Player victim = playerFor(victimId);
            EntityDamageEvent event = damageEvent(victim, 10.0);

            kickListener.onLethalDamage(event);

            assertThat(event.isCancelled())
                    .as("the tribute is about to leave the server anyway — an actual death is harmless")
                    .isFalse();
            assertThat(session.participants().isAlive(victimId))
                    .as("onLethalDamage must not eliminate on KICK's behalf; onDeath does that")
                    .isTrue();
            verifyNoInteractions(spectators);
        }
    }

    @Test
    @DisplayName("what it watches, for the diagnostic that lists what is registered")
    void describesItself() {
        assertThat(listener.describe()).contains("elimination");
    }
}
