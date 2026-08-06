package de.raindancer.modules.hungergames.service;

// In the service package rather than flat with the rest of the module's tests, because the collaborator
// interfaces this service satisfies — EventEndpoints.SupplyDrops and its SupplyDropSlot record — are
// package-private there. They are the contract between the services and the HTTP layer and have no business
// being public; the test goes to them rather than the other way round.

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.Tweak;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.SessionSnapshot;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.store.GameEvents;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.RuntimeStore;
import de.raindancer.modules.hungergames.store.SessionStore;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The schedule and the warning-to-landing delay, both driven by hand through {@link SupplyDropService#tick}
 * rather than by waiting on a real clock or a real world.
 */
@ExtendWith(MockitoExtension.class)
class SupplyDropServiceTest {

    private static final String LOOT_TABLE = "supply-drop";

    /** A no-op {@link GameEvents}: this test cares about the drop schedule, not what the session announces. */
    private static final class NoOpEvents implements GameEvents {
        @Override
        public void phaseChanged(GamePhase oldPhase, GamePhase newPhase) {
        }

        @Override
        public void participantEliminated(UUID participant, UUID killer, int remainingAlive) {
        }

        @Override
        public void participantRevived(UUID participant) {
        }

        @Override
        public void whitelistChanged(UUID player, boolean added) {
        }

        @Override
        public void teamCreated(Team team) {
        }

        @Override
        public void teamDeleted(Team team) {
        }

        @Override
        public void teamColourChanged(Team team, TeamColour oldColour, TeamColour newColour) {
        }

        @Override
        public void teamMembershipChanged(UUID player, TeamId oldTeam, TeamId newTeam, MembershipCause cause) {
        }

        @Override
        public void kill(UUID killer, UUID victim, int killerTotalKills) {
        }

        @Override
        public void winnerDeclared(Winner winner) {
        }
    }

    /** A no-op {@link SessionStore}: nothing here checks that a snapshot was written. */
    private static final class NoOpStore implements SessionStore {
        @Override
        public void save(SessionSnapshot snapshot) {
        }

        @Override
        public Optional<SessionSnapshot> load() {
            return Optional.empty();
        }

        @Override
        public void clear() {
        }
    }

    /** Always agrees to a spot at whatever offset it is asked for, one block above y=64. */
    private static final class FakeArena implements SupplyDropService.Arena {
        boolean refuseEverything;
        final World world;

        FakeArena(World world) {
            this.world = world;
        }

        @Override
        public Optional<Location> centre() {
            return Optional.empty();
        }

        @Override
        public Optional<Location> siteAt(int dx, int dz, boolean onlyOverworld) {
            if (refuseEverything) {
                return Optional.empty();
            }
            return Optional.of(new Location(world, dx, 64, dz));
        }

        @Override
        public Optional<World> worldNamed(String name) {
            return world.getName().equals(name) ? Optional.of(world) : Optional.empty();
        }
    }

    /** Records every landing. */
    private static final class FakeLanding implements SupplyDropService.Landing {
        final List<Location> placed = new ArrayList<>();

        @Override
        public void place(Location site, String lootTableKey, HungerGamesSettings settings) {
            placed.add(site);
        }
    }

    private GameSession session;
    private FakeArena arena;
    private FakeLanding landing;
    private final List<String> logs = new ArrayList<>();
    private final List<Duration> schedule = new ArrayList<>(List.of(Duration.ofSeconds(10)));

    @Mock
    private AnnouncementService announcements;
    @Mock
    private Audience broadcastAudience;

    private SupplyDropService service;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        session = new GameSession(TeamRules::defaults, new NoOpEvents(), new NoOpStore(), () -> 0L, new Random(1));
        World world = mock(World.class);
        // Only reached once a drop actually has a pending landing to persist, or on a restore — several
        // tests below never get that far, so this stub is lenient rather than required by every one.
        lenient().when(world.getName()).thenReturn("world");
        arena = new FakeArena(world);
        landing = new FakeLanding();
        RuntimeStore runtimeStore = new RuntimeStore(dir.resolve("runtime.yml"));
        VirtualTime virtualTime = new VirtualTime(() -> 0L);

        service = new SupplyDropService(session, virtualTime, () -> schedule, arena, landing, announcements,
                broadcastAudience, (category, message, location) -> logs.add(category + ":" + message),
                runtimeStore, new Random(2), LOOT_TABLE);
        service.settings(HungerGamesSettings.DEFAULTS);
        toRunning();
    }

    private void toRunning() {
        session.whitelistAdd(UUID.randomUUID(), "Katniss");
        session.whitelistAdd(UUID.randomUUID(), "Peeta");
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);
    }

    @Test
    @DisplayName("nothing lands before its scheduled time")
    void beforeItsTime() {
        service.tick(Duration.ofSeconds(9));

        assertThat(landing.placed).isEmpty();
        assertThat(service.schedule()).extracting("triggered").containsExactly(false);
    }

    @Test
    @DisplayName("a due drop announces a warning immediately, and lands only once the warning has run")
    void warningThenLanding() {
        HungerGamesSettings tweaked = Tweak.of(HungerGamesSettings.DEFAULTS,
                "supplyDropWarningSeconds", 5, "supplyDropCount", 1);
        service.settings(tweaked);

        service.tick(Duration.ofSeconds(10));
        assertThat(landing.placed)
                .as("the warning has not run yet")
                .isEmpty();
        assertThat(service.schedule()).extracting("triggered").containsExactly(true);

        service.tick(Duration.ofSeconds(14));
        assertThat(landing.placed).as("still short of the five-second warning").isEmpty();

        service.tick(Duration.ofSeconds(15));
        assertThat(landing.placed).hasSize(1);
    }

    @Test
    @DisplayName("a drop already fired is never triggered a second time")
    void firedOnce() {
        service.tick(Duration.ofSeconds(10));
        service.tick(Duration.ofSeconds(20));
        service.tick(Duration.ofSeconds(30));

        assertThat(service.schedule()).hasSize(1);
        assertThat(service.schedule().get(0).triggered()).isTrue();
    }

    @Test
    @DisplayName("no candidate spot found means the drop is skipped, not retried forever")
    void noSpotFound() {
        arena.refuseEverything = true;

        service.tick(Duration.ofSeconds(10));

        assertThat(landing.placed).isEmpty();
        assertThat(logs).anyMatch(line -> line.contains("no suitable landing spot"));
    }

    @Test
    @DisplayName("triggerNow refuses outside RUNNING")
    void triggerNowOutsideRunning() {
        session.declareTimeout();

        Optional<String> result = service.triggerNow("Haymitch");

        assertThat(result).isPresent();
        assertThat(landing.placed).isEmpty();
    }

    @Test
    @DisplayName("triggerNow refuses when supply drops are disabled")
    void triggerNowDisabled() {
        service.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "supplyDropsEnabled", false));

        Optional<String> result = service.triggerNow("Haymitch");

        // AssertJ's OptionalAssert#contains checks the wrapped value for exact equality, not substring —
        // this test cares only that the reason names the setting, so it unwraps first.
        assertThat(result).isPresent();
        assertThat(result.get()).contains("disabled (events.supply-drops.enabled)");
    }

    @Test
    @DisplayName("triggerNow begins a drop right away, warning included")
    void triggerNowWorks() {
        service.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "supplyDropWarningSeconds", 1));

        Optional<String> result = service.triggerNow("Haymitch");

        assertThat(result).isEmpty();
        assertThat(logs).anyMatch(line -> line.contains("Haymitch"));
    }

    @Test
    @DisplayName("statusLine reports how many of the schedule have fired")
    void statusLineCounts() {
        assertThat(service.statusLine()).isEqualTo("0/1 triggered");

        service.tick(Duration.ofSeconds(10));

        assertThat(service.statusLine()).isEqualTo("1/1 triggered");
    }

    @Test
    @DisplayName("statusLine says so when supply drops are switched off")
    void statusLineDisabled() {
        service.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "supplyDropsEnabled", false));

        assertThat(service.statusLine()).isEqualTo("disabled");
    }

    @Test
    @DisplayName("a drop still airborne when the server went down lands immediately on restore")
    void restoreLandsImmediately(@TempDir Path dir) {
        RuntimeStore store = new RuntimeStore(dir.resolve("runtime.yml"));
        store.saveSupplyDropState(new RuntimeStore.SupplyDropState(java.util.Set.of(),
                List.of("world,10,64,20")));
        VirtualTime virtualTime = new VirtualTime(() -> 0L);
        SupplyDropService restoring = new SupplyDropService(session, virtualTime, () -> schedule, arena, landing,
                announcements, broadcastAudience, (c, m, l) -> logs.add(c + ":" + m), store, new Random(3),
                LOOT_TABLE);
        restoring.settings(HungerGamesSettings.DEFAULTS);

        restoring.restoreFromStore();

        assertThat(landing.placed).hasSize(1);
        assertThat(landing.placed.get(0).getBlockX()).isEqualTo(10);
        assertThat(logs).anyMatch(line -> line.contains("landed now"));
    }

    @Test
    @DisplayName("a candidate offset always falls between the configured radii")
    void candidateOffsetDistribution() {
        Random random = new Random(42);
        for (int i = 0; i < 200; i++) {
            int[] offset = SupplyDropService.candidateOffset(random, 10, 20);
            double distance = Math.hypot(offset[0], offset[1]);
            assertThat(distance).isBetween(9.0, 21.0); // rounding can nudge it by up to one block
        }
    }
}
