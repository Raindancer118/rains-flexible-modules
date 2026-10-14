package de.raindancer.modules.enchants.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CrystalWitherTest {

    /** A little world: what stands where, air everywhere else. */
    private static final class World implements CrystalWither.Grid {
        final Map<String, CrystalWither.Part> blocks = new HashMap<>();

        void put(int x, int y, int z, CrystalWither.Part part) {
            blocks.put(x + "," + y + "," + z, part);
        }

        @Override
        public CrystalWither.Part at(int x, int y, int z) {
            return blocks.getOrDefault(x + "," + y + "," + z, CrystalWither.Part.AIR);
        }
    }

    /** The build at base 10 64 10, along x or along z. */
    private static World build(boolean alongX) {
        World world = new World();
        int dx = alongX ? 1 : 0;
        int dz = alongX ? 0 : 1;
        world.put(10, 64, 10, CrystalWither.Part.AMETHYST);
        for (int side = -1; side <= 1; side++) {
            world.put(10 + side * dx, 65, 10 + side * dz, CrystalWither.Part.AMETHYST);
            world.put(10 + side * dx, 66, 10 + side * dz, CrystalWither.Part.SKULL);
        }
        return world;
    }

    @Test
    @DisplayName("a T of amethyst with three wither skulls on top is found from whichever skull was placed last")
    void found() {
        for (boolean alongX : new boolean[]{true, false}) {
            World world = build(alongX);
            for (int side = -1; side <= 1; side++) {
                int x = 10 + (alongX ? side : 0);
                int z = 10 + (alongX ? 0 : side);
                assertThat(CrystalWither.find(world, x, 66, z)).as("along x " + alongX + ", skull " + side)
                        .hasValueSatisfying(found -> {
                            assertThat(found.blocks()).hasSize(7);
                            assertThat(found.base()).containsExactly(10, 64, 10);
                        });
            }
        }
    }

    @Test
    @DisplayName("not with a block where the lower corners must be empty, nor with a missing skull or soul sand")
    void notFound() {
        World corner = build(true);
        corner.put(9, 64, 10, CrystalWither.Part.OTHER);
        assertThat(CrystalWither.find(corner, 10, 66, 10)).isEmpty();

        World twoSkulls = build(true);
        twoSkulls.put(11, 66, 10, CrystalWither.Part.AIR);
        assertThat(CrystalWither.find(twoSkulls, 10, 66, 10)).isEmpty();

        World soulSand = build(true);
        soulSand.put(10, 64, 10, CrystalWither.Part.OTHER);
        assertThat(CrystalWither.find(soulSand, 10, 66, 10)).isEmpty();
    }

    @Test
    @DisplayName("a Crystal Wither drops a ton of shards: forty to sixty")
    void loot() {
        java.util.SplittableRandom random = new java.util.SplittableRandom(7);
        int least = Integer.MAX_VALUE;
        int most = 0;
        for (int i = 0; i < 1_000; i++) {
            int shards = CrystalWither.shards(random);
            least = Math.min(least, shards);
            most = Math.max(most, shards);
        }
        assertThat(least).isGreaterThanOrEqualTo(40);
        assertThat(most).isLessThanOrEqualTo(60).isGreaterThan(55);
    }
}
