package de.raindancer.modules.hungergames;

import de.raindancer.core.world.safety.Spot;
import de.raindancer.core.world.spawn.Wave;
import de.raindancer.core.world.spawn.Spawns;
import de.raindancer.modules.hungergames.service.MonsterWaveService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The scheduling on top of Core's own {@link Wave}/{@link Spawns} — which packs are due, given the round's
 * elapsed time, and nothing about the ring of spawn points itself, which is Core's job and already tested
 * there.
 */
@ExtendWith(MockitoExtension.class)
class MonsterWaveServiceTest {

    @Mock
    private World world;

    private MonsterWaveService service;
    private Location centre;

    @BeforeEach
    void setUp() {
        // Only reached once start() has passed every validation and actually builds a Spot; several tests
        // below are checking that validation refuses *before* that point, so this stub is lenient rather
        // than required by every test.
        lenient().when(world.getName()).thenReturn("world");
        centre = new Location(world, 10, 64, 20);
        service = new MonsterWaveService(new Spawns(spawner), (c, m, l) -> logs.add(c + ":" + m), new Random(1));
        service.settings(HungerGamesSettings.DEFAULTS);
    }

    private final List<String> logs = new ArrayList<>();
    private final List<String> spawnedTypes = new ArrayList<>();
    private final de.raindancer.core.world.spawn.Spawner spawner = new de.raindancer.core.world.spawn.Spawner() {
        @Override
        public boolean spawn(Spot spot, String type) {
            spawnedTypes.add(type);
            return true;
        }
    };

    @Test
    @DisplayName("an unspawnable name is refused before anything is scheduled")
    void refusesAnUnspawnableName() {
        Optional<String> result = service.start(centre, "NOT_A_MOB", 3, 2, 5, "GM");

        assertThat(result).isPresent();
        assertThat(service.activeSeries()).isZero();
    }

    @Test
    @DisplayName("count and waves must both be positive")
    void refusesNonPositiveCounts() {
        assertThat(service.start(centre, "ZOMBIE", 0, 2, 5, "GM")).isPresent();
        assertThat(service.start(centre, "ZOMBIE", 3, 0, 5, "GM")).isPresent();
    }

    @Test
    @DisplayName("the first wave fires on the very next tick, at the round's current elapsed time")
    void firstWaveFiresImmediately() {
        Optional<String> result = service.start(centre, "ZOMBIE", 3, 2, 5, "GM", Duration.ofSeconds(100));
        assertThat(result).isEmpty();

        service.tick(Duration.ofSeconds(100));

        assertThat(spawnedTypes).hasSize(3);
        assertThat(spawnedTypes).allMatch(type -> type.equals("ZOMBIE"));
    }

    @Test
    @DisplayName("a later wave does not fire before its own interval has passed")
    void laterWaveWaitsForItsInterval() {
        service.start(centre, "ZOMBIE", 2, 2, 5, "GM", Duration.ZERO);

        service.tick(Duration.ZERO);
        assertThat(spawnedTypes).hasSize(2);

        service.tick(Duration.ofSeconds(4));
        assertThat(spawnedTypes)
                .as("the second wave is five seconds after the first")
                .hasSize(2);

        service.tick(Duration.ofSeconds(5));
        assertThat(spawnedTypes).hasSize(4);
    }

    @Test
    @DisplayName("a series is removed once every wave has fired")
    void seriesEndsAfterItsLastWave() {
        service.start(centre, "ZOMBIE", 1, 2, 1, "GM", Duration.ZERO);

        service.tick(Duration.ZERO);
        assertThat(service.activeSeries()).isEqualTo(1);

        service.tick(Duration.ofSeconds(1));
        assertThat(service.activeSeries()).isZero();
    }

    @Test
    @DisplayName("stopAll removes every series and reports how many")
    void stopAllReportsCount() {
        service.start(centre, "ZOMBIE", 1, 5, 1, "GM", Duration.ZERO);
        service.start(centre, "SKELETON", 1, 5, 1, "GM", Duration.ZERO);

        int stopped = service.stopAll();

        assertThat(stopped).isEqualTo(2);
        assertThat(service.activeSeries()).isZero();
        service.tick(Duration.ofSeconds(100));
        assertThat(spawnedTypes).isEmpty();
    }

    @Test
    @DisplayName("a series without a valid location is refused")
    void refusesWithoutAWorld() {
        Location noWorld = new Location(null, 0, 0, 0);

        assertThat(service.start(noWorld, "ZOMBIE", 1, 1, 1, "GM")).isPresent();
    }

    @Test
    @DisplayName("resolveMonster accepts only spawnable, living monsters")
    void resolveMonsterValidatesTheType() {
        assertThat(MonsterWaveService.resolveMonster("zombie")).isEqualTo(EntityType.ZOMBIE);
        assertThat(MonsterWaveService.resolveMonster("not-a-mob")).isNull();
        assertThat(MonsterWaveService.resolveMonster(null)).isNull();
        // ARROW is spawnable-ish infrastructure, not a living monster — must be refused.
        assertThat(MonsterWaveService.resolveMonster("ARROW")).isNull();
    }

    @Test
    @DisplayName("the defaults come straight from settings")
    void defaultsComeFromSettings() {
        HungerGamesSettings tweaked = Tweak.of(HungerGamesSettings.DEFAULTS,
                "monsterWaveDefaultMob", "SKELETON", "monsterWaveCountPerWave", 7,
                "monsterWaveWaveCount", 4, "monsterWaveIntervalSeconds", 9);
        service.settings(tweaked);

        assertThat(service.defaultMob()).isEqualTo("SKELETON");
        assertThat(service.defaultCount()).isEqualTo(7);
        assertThat(service.defaultWaves()).isEqualTo(4);
        assertThat(service.defaultInterval()).isEqualTo(9);
    }
}
