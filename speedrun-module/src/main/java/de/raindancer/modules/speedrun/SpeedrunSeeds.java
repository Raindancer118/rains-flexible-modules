package de.raindancer.modules.speedrun;

import de.raindancer.core.world.manage.WorldSeed;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Where the next world's seed comes from, and what kind of seed a world turned out to have.
 *
 * <p>Pure: settings in, a {@link WorldSeed} out. {@code seed} and every entry of {@code seed-pool}
 * are read the way the vanilla create-world screen reads them — a number is that seed, a word is
 * hashed the way Minecraft hashes it — through Core's {@link WorldSeed#parse}.
 */
public final class SpeedrunSeeds {

    private SpeedrunSeeds() {
    }

    /** The seed the next reset should make the run's worlds from. */
    public static WorldSeed next(SpeedrunSettings settings, RandomGenerator random) {
        SpeedrunSeedMode mode = settings.seedMode() == null ? SpeedrunSeedMode.RANDOM : settings.seedMode();
        return switch (mode) {
            case RANDOM -> WorldSeed.random();
            case FIXED -> fixed(settings.seed()).map(WorldSeed::fixed).orElse(WorldSeed.random());
            case POOL -> {
                List<Long> pool = pool(settings.seedPool());
                yield pool.isEmpty() ? WorldSeed.random() : WorldSeed.fixed(pool.get(random.nextInt(pool.size())));
            }
        };
    }

    /**
     * Whether {@code worldSeed} is one somebody could have known: the configured seed, one of the
     * pool, or any seed a past run was already played on. A replayed random seed is a set seed.
     */
    public static SpeedrunSeedType typeOf(long worldSeed, SpeedrunSettings settings, SpeedrunHistory history) {
        if (fixed(settings.seed()).filter(seed -> seed == worldSeed).isPresent()
                || pool(settings.seedPool()).contains(worldSeed)
                || (history != null && history.played(worldSeed))) {
            return SpeedrunSeedType.SET;
        }
        return SpeedrunSeedType.RANDOM;
    }

    /** {@code seed}, as a seed — empty for nothing typed or {@code random}. */
    public static Optional<Long> fixed(String typed) {
        return WorldSeed.parse(typed)
                .filter(seed -> seed.kind() == WorldSeed.Kind.FIXED)
                .map(WorldSeed::value);
    }

    /** {@code seed-pool}: seeds separated by commas, spaces or semicolons. */
    public static List<Long> pool(String typed) {
        List<Long> seeds = new ArrayList<>();
        if (typed == null) {
            return seeds;
        }
        for (String one : typed.split("[,;\\s]+")) {
            fixed(one).ifPresent(seeds::add);
        }
        return seeds;
    }
}
