package de.raindancer.modules.hungergames;

import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.SpectatorService;
import de.raindancer.modules.hungergames.store.GameEvents.MembershipCause;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.Location;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SpectatorService} without {@code GameMode.SPECTATOR} — vanished instead, so the hotbar and the
 * inventory survive being eliminated. See the class javadoc for why not real spectator mode.
 */
@ExtendWith(MockitoExtension.class)
class SpectatorServiceTest {

    private final Map<UUID, Player> online = new HashMap<>();
    private final java.util.List<Player> teleportedTo = new java.util.ArrayList<>();
    private final java.util.List<Player> teleportedFrom = new java.util.ArrayList<>();

    private GameSession session;
    private Vanish vanish;
    private CustomItems items;
    private ItemFactory itemFactory;
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

        vanish = mock(Vanish.class);
        items = mock(CustomItems.class);
        itemFactory = mock(ItemFactory.class);

        service = new SpectatorService(session, uuid -> Optional.ofNullable(online.get(uuid)),
                (spectator, target) -> {
                    teleportedFrom.add(spectator);
                    teleportedTo.add(target);
                },
                vanish, mock(ItemAbilities.class), items, itemFactory,
                player -> { });
    }

    private Player playerFor(UUID uuid) {
        Player player = mock(Player.class);
        // Lenient: not every test below asks a player for their own UUID, their location or their
        // inventory — teleportTo() is handed the target UUID directly, for instance.
        org.mockito.Mockito.lenient().when(player.getUniqueId()).thenReturn(uuid);
        org.mockito.Mockito.lenient().when(player.getLocation())
                .thenReturn(new Location(null, 0, 64, 0));
        org.mockito.Mockito.lenient().when(player.getInventory())
                .thenReturn(mock(PlayerInventory.class));
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

    @Nested
    @DisplayName("makeSpectator")
    class MakingASpectator {

        @Test
        @DisplayName("vanishes without a fake departure — they never left in any sense that would be honest")
        void vanishesSilently() {
            Player victimPlayer = playerFor(victim);
            when(victimPlayer.getAllowFlight()).thenReturn(false);

            service.makeSpectator(victimPlayer);

            verify(vanish).vanish(victim, false, false);
        }

        @Test
        @DisplayName("remembers whether they could already fly, for a correct reveal later")
        void rememberssExistingFlight() {
            Player victimPlayer = playerFor(victim);
            when(victimPlayer.getAllowFlight()).thenReturn(true);

            service.makeSpectator(victimPlayer);

            verify(vanish).vanish(victim, true, false);
        }

        @Test
        @DisplayName("grants flight, at more than vanilla's own speed")
        void grantsFasterFlight() {
            Player victimPlayer = playerFor(victim);

            service.makeSpectator(victimPlayer);

            verify(victimPlayer).setAllowFlight(true);
            verify(victimPlayer).setFlying(true);
            verify(victimPlayer).setFlySpeed(org.mockito.ArgumentMatchers.floatThat(speed -> speed > 0.1F));
        }

        @Test
        @DisplayName("remembers where they stood, for the respawn that follows")
        void remembersWhereTheyStood() {
            Player victimPlayer = playerFor(victim);
            Location where = new Location(null, 12, 34, 56);
            when(victimPlayer.getLocation()).thenReturn(where);

            service.makeSpectator(victimPlayer);

            assertThat(service.lastKnownLocation(victim)).contains(where);
        }
    }

    @Nested
    @DisplayName("restoreFromElimination")
    class RestoringFromElimination {

        @Test
        @DisplayName("reveals, stops flying, and forgets the remembered location")
        void undoesEverything() {
            Player victimPlayer = playerFor(victim);
            service.makeSpectator(victimPlayer);

            service.restoreFromElimination(victimPlayer);

            verify(vanish).reveal(victim);
            verify(victimPlayer).setFlying(false);
            assertThat(service.lastKnownLocation(victim)).isEmpty();
        }
    }

    @Test
    @DisplayName("what it calls itself, for the console line listing what started")
    void itSaysWhatItIs() {
        assertThat(service.describe()).contains("spectator");
    }
}
