package de.raindancer.modules.speedrun;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.world.manage.WorldSeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SpeedrunSeedsTest {

    @TempDir
    Path folder;

    private static SpeedrunSettings with(SpeedrunSeedMode mode, String seed, String pool) {
        SpeedrunSettings d = SpeedrunSettings.DEFAULTS;
        return new SpeedrunSettings(d.gameMode(), d.worldName(), d.advancementKey(), d.clearAdvancementsOnStart(),
                d.deathPolicy(), d.requireExitPortalAfterDragon(), 0, 0, 0, 0, false, 0, 0, 0, 0, 0,
                true, true, true, true, true, 10, true, 1000, true, true, true, true, true, true, true, true, true,
                true, false, false, mode, seed, pool, 12, SpeedrunHudMode.SIDEBAR, true, true, false,
                SpeedrunPracticeKit.NONE, false);
    }

    @Test
    @DisplayName("RANDOM gives a random seed; FIXED the seed; a word is hashed like the create-world screen")
    void modes() {
        assertThat(SpeedrunSeeds.next(with(SpeedrunSeedMode.RANDOM, "5", ""), new Random(1)))
                .isEqualTo(WorldSeed.random());
        assertThat(SpeedrunSeeds.next(with(SpeedrunSeedMode.FIXED, "-4172144997902289642", ""), new Random(1)))
                .isEqualTo(WorldSeed.fixed(-4172144997902289642L));
        assertThat(SpeedrunSeeds.next(with(SpeedrunSeedMode.FIXED, "speedrun", ""), new Random(1)))
                .isEqualTo(WorldSeed.fixed("speedrun".hashCode()));
    }

    @Test
    @DisplayName("FIXED without a seed, or POOL without one, fall back to random rather than to seed 0")
    void emptyFallsBackToRandom() {
        assertThat(SpeedrunSeeds.next(with(SpeedrunSeedMode.FIXED, "", ""), new Random(1))).isEqualTo(WorldSeed.random());
        assertThat(SpeedrunSeeds.next(with(SpeedrunSeedMode.POOL, "", " , "), new Random(1))).isEqualTo(WorldSeed.random());
    }

    @Test
    @DisplayName("POOL picks one of the pool")
    void pool() {
        SpeedrunSettings settings = with(SpeedrunSeedMode.POOL, "", "1, 2;3 4");

        for (int i = 0; i < 20; i++) {
            assertThat(SpeedrunSeeds.next(settings, new Random(i)).value()).isBetween(1L, 4L);
        }
        assertThat(SpeedrunSeeds.pool("1, 2;3 4")).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("a world is a set seed when it is the configured seed, in the pool, or was played before")
    void seedType() {
        SpeedrunHistory history = new SpeedrunHistory(new YamlStore(folder.resolve("h.yml")), Runnable::run);
        assertThat(SpeedrunSeeds.typeOf(99, with(SpeedrunSeedMode.RANDOM, "", ""), history))
                .isEqualTo(SpeedrunSeedType.RANDOM);
        assertThat(SpeedrunSeeds.typeOf(99, with(SpeedrunSeedMode.RANDOM, "99", ""), history))
                .isEqualTo(SpeedrunSeedType.SET);
        assertThat(SpeedrunSeeds.typeOf(99, with(SpeedrunSeedMode.RANDOM, "", "1 99"), history))
                .isEqualTo(SpeedrunSeedType.SET);

        history.add(new SpeedrunRunRecord("r", SpeedrunHistoryTest.DRAGON, 1, Duration.ofMinutes(1), "x", false,
                99, java.util.Map.of(), java.util.List.of(), java.util.Map.of()));
        assertThat(SpeedrunSeeds.typeOf(99, with(SpeedrunSeedMode.RANDOM, "", ""), history))
                .as("a random seed played again is a seed somebody has seen").isEqualTo(SpeedrunSeedType.SET);
    }
}
