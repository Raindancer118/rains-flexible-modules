package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.service.AnnouncementService;
import de.raindancer.modules.hungergames.service.SponsorBeaconService;
import de.raindancer.modules.hungergames.service.SponsorTokenService;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.RuntimeStore;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The registry of active beacons and the random-timed spawn schedule, kept apart from
 * {@link SponsorTokenService} so either can be tested without the other — see the class note on the split.
 */
@ExtendWith(MockitoExtension.class)
class SponsorBeaconServiceTest {

    /** Places the beacon wherever asked, doing nothing to an actual world. */
    private static final class RecordingBlock implements SponsorBeaconService.BeaconBlock {
        final List<Location> placed = new ArrayList<>();
        final List<Location> removed = new ArrayList<>();

        @Override
        public void place(Location site, HungerGamesSettings settings) {
            placed.add(site);
        }

        @Override
        public void remove(Location site) {
            removed.add(site);
        }
    }

    /** Agrees to every candidate spot it is offered. */
    private static final class AgreeableArena implements SponsorBeaconService.Arena {
        final World world;
        boolean refuseEverything;

        AgreeableArena(World world) {
            this.world = world;
        }

        @Override
        public Optional<Location> centre() {
            return Optional.of(new Location(world, 0, 64, 0));
        }

        @Override
        public Optional<Location> siteAt(int dx, int dz) {
            return refuseEverything ? Optional.empty() : Optional.of(new Location(world, dx, 64, dz));
        }

        @Override
        public Optional<World> worldNamed(String name) {
            return world.getName().equals(name) ? Optional.of(world) : Optional.empty();
        }
    }

    @Mock
    private World world;
    @Mock
    private AnnouncementService announcements;
    @Mock
    private Audience broadcastAudience;
    @Mock
    private SponsorTokenService tokens;

    private GameSession session;
    private RecordingBlock block;
    private AgreeableArena arena;
    private RuntimeStore runtimeStore;
    private final List<String> logs = new ArrayList<>();

    private SponsorBeaconService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        session = new GameSession(TeamRules::defaults, new RecordingGameEvents(),
                new InMemorySessionStore(), () -> 0L, new Random(1));
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);

        // Only reached once a beacon is actually persisted (createBeacon/removeBeacon call through to
        // RuntimeStore, which serialises every active beacon's world name) — a couple of tests below never
        // create one, so this stub is lenient rather than required by every test.
        org.mockito.Mockito.lenient().when(world.getName()).thenReturn("world");

        block = new RecordingBlock();
        arena = new AgreeableArena(world);
        runtimeStore = new RuntimeStore(tempDir.resolve("runtime.yml"));
        service = new SponsorBeaconService(session, arena, block, announcements, broadcastAudience,
                (category, message, location) -> logs.add(category + ":" + message), runtimeStore, tokens,
                new Random(2));
        service.settings(HungerGamesSettings.DEFAULTS);
    }

    @Test
    @DisplayName("creating a beacon places the block and registers the location")
    void createBeaconPlacesAndRegisters() {
        Location site = new Location(world, 5, 64, 5);

        Optional<String> result = service.createBeacon(site, "Haymitch");

        assertThat(result).isEmpty();
        assertThat(block.placed).containsExactly(site);
        assertThat(service.activeBeacons()).contains(site);
        assertThat(logs).anyMatch(line -> line.contains("Haymitch"));
    }

    @Test
    @DisplayName("a location with no world is refused")
    void refusesALocationWithNoWorld() {
        Location noWorld = new Location(null, 0, 0, 0);

        assertThat(service.createBeacon(noWorld, "Haymitch")).isPresent();
        assertThat(block.placed).isEmpty();
    }

    @Test
    @DisplayName("removeAllBeacons removes every registered beacon and reports how many")
    void removeAllRemovesEverything() {
        service.createBeacon(new Location(world, 1, 64, 1), "GM");
        service.createBeacon(new Location(world, 2, 64, 2), "GM");

        int removed = service.removeAllBeacons("Haymitch");

        assertThat(removed).isEqualTo(2);
        assertThat(service.activeBeacons()).isEmpty();
        assertThat(block.removed).hasSize(2);
    }

    @Test
    @DisplayName("token questions are delegated to SponsorTokenService, not answered here")
    void tokenQuestionsAreDelegated() {
        when(tokens.tokensEnabled()).thenReturn(true);
        Player target = mock(Player.class);

        assertThat(service.tokensEnabled()).isTrue();
        service.giveManually("Haymitch", target, 5);
        verify(tokens).giveManually("Haymitch", target, 5);

        when(tokens.clearTokens(target)).thenReturn(3);
        assertThat(service.clearTokens(target)).isEqualTo(3);
    }

    @Test
    @DisplayName("a random-timed spawn that finds no site logs it and creates nothing")
    void randomSpawnWithNoSiteFound() {
        arena.refuseEverything = true;

        service.tick(Duration.ofSeconds(10), () -> List.of(Duration.ofSeconds(10)));

        assertThat(service.activeBeacons()).isEmpty();
        assertThat(logs).anyMatch(line -> line.contains("no suitable spot"));
    }

    @Test
    @DisplayName("a due random-timed slot spawns exactly one beacon, once")
    void dueRandomSlotSpawnsOnce() {
        service.tick(Duration.ofSeconds(10), () -> List.of(Duration.ofSeconds(10)));
        assertThat(service.activeBeacons()).hasSize(1);

        service.tick(Duration.ofSeconds(20), () -> List.of(Duration.ofSeconds(10)));
        assertThat(service.activeBeacons())
                .as("the same slot must not fire a second beacon")
                .hasSize(1);
    }

    @Test
    @DisplayName("nothing spawns outside RUNNING")
    void nothingOutsideRunning() {
        session.declareTimeout();

        service.tick(Duration.ofSeconds(10), () -> List.of(Duration.ofSeconds(10)));

        assertThat(service.activeBeacons()).isEmpty();
    }

    @Test
    @DisplayName("resetForNewRound empties the registry")
    void resetEmptiesTheRegistry() {
        service.createBeacon(new Location(world, 1, 64, 1), "GM");

        service.resetForNewRound();

        assertThat(service.activeBeacons()).isEmpty();
    }

    @Test
    @DisplayName("isSponsorBeacon answers only for a registered location")
    void isSponsorBeaconChecksTheRegistry() {
        Location site = new Location(world, 5, 64, 5);
        service.createBeacon(site, "GM");

        assertThat(service.isSponsorBeacon(site)).isTrue();
        assertThat(service.isSponsorBeacon(new Location(world, 99, 64, 99))).isFalse();
    }

    @Test
    @DisplayName("a beacon and a fired spawn slot both survive a restart, through RuntimeStore")
    void beaconStateSurvivesARestart() {
        service.tick(Duration.ofSeconds(10), () -> List.of(Duration.ofSeconds(10))); // spawns one beacon
        assertThat(service.activeBeacons()).hasSize(1);

        SponsorBeaconService restarted = new SponsorBeaconService(session, arena, block, announcements,
                broadcastAudience, (c, m, l) -> logs.add(c + ":" + m), runtimeStore, tokens, new Random(3));
        restarted.settings(HungerGamesSettings.DEFAULTS);
        restarted.start();

        assertThat(restarted.activeBeacons()).isEqualTo(service.activeBeacons());
        // The slot must not fire a second beacon after the restart either.
        restarted.tick(Duration.ofSeconds(20), () -> List.of(Duration.ofSeconds(10)));
        assertThat(restarted.activeBeacons()).hasSize(1);
    }
}
