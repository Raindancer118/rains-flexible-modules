package de.raindancer.modules.hungergames;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.SpectatorService;
import de.raindancer.modules.hungergames.store.GameEvents.MembershipCause;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The one door onto a living tribute: {@link SpectatorService#teleportTo} and the preference it uses to
 * pick a fresh spectator's first target.
 */
@ExtendWith(MockitoExtension.class)
class SpectatorServiceTest {

    private final Map<UUID, Player> online = new HashMap<>();
    private final java.util.List<Player> teleportedTo = new java.util.ArrayList<>();
    private final java.util.List<Player> teleportedFrom = new java.util.ArrayList<>();
    private final java.util.List<Player> switchedToSpectator = new java.util.ArrayList<>();

    private GameSession session;
    private SpectatorService service;

    private final UUID victim = UUID.randomUUID();
    private final UUID teammate = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), () -> 0L, new Random(1));
        session.whitelistAdd(victim, "Victim");
        session.whitelistAdd(teammate, "Teammate");
        session.whitelistAdd(stranger, "Stranger");

        service = new SpectatorService(session, uuid -> Optional.ofNullable(online.get(uuid)),
                (spectator, target) -> {
                    teleportedFrom.add(spectator);
                    teleportedTo.add(target);
                },
                switchedToSpectator::add);
    }

    private Player playerFor(UUID uuid) {
        Player player = mock(Player.class);
        // Lenient: only makeSpectator() ever asks a Player for their own UUID (to find their first
        // target); teleportTo() is handed the target UUID directly, so most tests below never touch this.
        org.mockito.Mockito.lenient().when(player.getUniqueId()).thenReturn(uuid);
        online.put(uuid, player);
        return player;
    }

    @Test
    @DisplayName("teleport refuses a target who is not alive")
    void refusesADeadTarget() {
        Player spectator = playerFor(victim);
        playerFor(teammate);
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);
        session.eliminate(teammate, null);

        assertThat(service.teleportTo(spectator, teammate)).isFalse();
        assertThat(teleportedTo).isEmpty();
    }

    @Test
    @DisplayName("teleport refuses a living target who is not online")
    void refusesAnOfflineTarget() {
        Player spectator = playerFor(victim);

        assertThat(service.teleportTo(spectator, teammate)).isFalse();
        assertThat(teleportedTo).isEmpty();
    }

    @Test
    @DisplayName("teleport succeeds for a living, online target")
    void succeedsForALivingOnlineTarget() {
        Player spectator = playerFor(victim);
        Player target = playerFor(teammate);

        assertThat(service.teleportTo(spectator, teammate)).isTrue();
        assertThat(teleportedFrom).containsExactly(spectator);
        assertThat(teleportedTo).containsExactly(target);
    }

    @Test
    @DisplayName("firstTarget prefers a living, online teammate over a stranger")
    void firstTargetPrefersATeammate() {
        var team = session.teamCreate("Careers", TeamColour.RED).team().orElseThrow();
        session.teamAssign(victim, team.id(), MembershipCause.API);
        session.teamAssign(teammate, team.id(), MembershipCause.API);
        session.teamAssign(stranger, session.teamCreate("Loners", TeamColour.BLUE).team()
                .orElseThrow().id(), MembershipCause.API);
        playerFor(teammate);
        playerFor(stranger);

        Optional<UUID> chosen = service.firstTarget(victim);

        assertThat(chosen).contains(teammate);
    }

    @Test
    @DisplayName("firstTarget falls back to any living, online tribute with no online teammate")
    void firstTargetFallsBackToAnybody() {
        playerFor(stranger); // teammate stays offline

        Optional<UUID> chosen = service.firstTarget(victim);

        assertThat(chosen).contains(stranger);
    }

    @Test
    @DisplayName("firstTarget is empty when nobody eligible is online")
    void firstTargetEmptyWhenNobodyIsOnline() {
        assertThat(service.firstTarget(victim)).isEmpty();
    }

    @Test
    @DisplayName("makeSpectator switches the mode and points at the first sensible target")
    void makeSpectatorSwitchesAndTeleports() {
        Player victimPlayer = playerFor(victim);
        Player strangerPlayer = playerFor(stranger);

        service.makeSpectator(victimPlayer);

        assertThat(switchedToSpectator).containsExactly(victimPlayer);
        assertThat(teleportedTo).containsExactly(strangerPlayer);
    }

    @Test
    @DisplayName("what it calls itself, for the console line listing what started")
    void itSaysWhatItIs() {
        assertThat(service.describe()).contains("spectator");
    }
}
