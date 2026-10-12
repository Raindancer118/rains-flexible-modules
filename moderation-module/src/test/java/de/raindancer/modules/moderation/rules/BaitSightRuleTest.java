package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.util.BlockGrid;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client breaks blocks the moment it digs them and only then tells the server. With an
 * Efficiency V pickaxe in netherrack that is instant, so a bait next to the block flashed up for one
 * ping before the server could take it back. The bait has to go while the tunnel is still further away.
 */
class BaitSightRuleTest {

    private final BaitSightRule rule = new BaitSightRule();

    /** Progress per tick: netherite pickaxe with Efficiency V (9 + 5² + 1). */
    private static final double NETHERITE_EFF5 = 35;

    @Test
    @DisplayName("Efficiency V netherite breaks netherrack instantly, an iron pickaxe does not break stone fast")
    void progress() {
        assertThat(BaitSightRule.progress(NETHERITE_EFF5, 0, 1, 0.4f, true)).isGreaterThanOrEqualTo(1);
        assertThat(BaitSightRule.progress(6, 0, 1, 1.5f, true)).isLessThan(0.2);
        assertThat(BaitSightRule.progress(6, 2, 1, 1.5f, true)).isGreaterThan(BaitSightRule.progress(6, 0, 1, 1.5f, true));
        assertThat(BaitSightRule.progress(6, 0, 1, -1f, true)).isZero();
    }

    @Test
    @DisplayName("a slow pickaxe keeps the old rule: only a dig next to the bait takes it back")
    void slowDiggingKeepsOneBlock() {
        double iron = BaitSightRule.progress(6, 0, 1, 1.5f, true);
        assertThat(rule.distance(iron, 50)).isEqualTo(1);
    }

    @Test
    @DisplayName("instant mining needs more room the higher the ping, up to the cap")
    void instantMiningNeedsRoom() {
        assertThat(rule.distance(3, 0)).isGreaterThan(1);
        assertThat(rule.distance(3, 150)).isGreaterThan(rule.distance(3, 20));
        assertThat(rule.distance(3, 5000)).isEqualTo(BaitSightRule.MAX_DISTANCE);
    }

    /**
     * The client digs a straight line towards a bait; the server hears of each dig after half a
     * ping and up to a tick, and its answer takes the other half. Does the last block before the
     * bait break on the client before the bait is gone?
     */
    private static boolean flashes(double progress, int ping, int distance, int baitAt) {
        boolean instant = progress >= 1;
        long digMillis = instant ? 0 : (long) Math.ceil(1 / progress) * 50;
        long start = 0;
        long restored = Long.MAX_VALUE;
        for (int block = 1; block < baitAt; block++) {
            // The server counts a block as open from the moment its dig starts.
            if (restored == Long.MAX_VALUE && baitAt - block <= distance) {
                restored = start + ping + 50;
            }
            long finish = start + digMillis;
            if (block == baitAt - 1) {
                return finish <= restored;
            }
            start = finish + (instant ? 50 : 5 * 50);
        }
        return false;
    }

    @Test
    @DisplayName("the old one-block rule let an instant miner see the bait")
    void oldRuleFlashes() {
        assertThat(flashes(3, 50, 1, 8)).isTrue();
    }

    @Test
    @DisplayName("no tool and no ping up to 250 ms lets the client break into a bait before it is gone")
    void noFlash() {
        for (double progress : new double[]{3, 1, 0.78, 0.39, 0.2, 0.13, 0.05}) {
            for (int ping = 0; ping <= 250; ping += 5) {
                int distance = rule.distance(progress, ping);
                for (int baitAt = distance + 1; baitAt <= 14; baitAt++) {
                    assertThat(flashes(progress, ping, distance, baitAt))
                            .as("progress %s, ping %d, distance %d, bait %d away", progress, ping, distance, baitAt)
                            .isFalse();
                }
            }
        }
    }

    /** Stone, with whatever is listed open. */
    private static final class Rock implements BlockGrid {
        final Set<List<Integer>> air = new HashSet<>();

        Rock air(int x, int y, int z) {
            air.add(List.of(x, y, z));
            return this;
        }

        @Override
        public Material at(int x, int y, int z) {
            return open(x, y, z) ? Material.AIR : Material.STONE;
        }

        @Override
        public boolean open(int x, int y, int z) {
            return air.contains(List.of(x, y, z));
        }
    }

    @Test
    @DisplayName("distance to open space counts steps along the axes")
    void openDistance() {
        Rock rock = new Rock().air(3, 0, 0).air(1, 1, 0);
        assertThat(BaitSightRule.openDistance(rock, 0, 0, 0, 5)).isEqualTo(2);
        assertThat(BaitSightRule.openDistance(rock, 0, 0, 0, 1)).isEqualTo(2);
        assertThat(BaitSightRule.openDistance(new Rock(), 0, 0, 0, 4)).isEqualTo(5);
    }

    @Test
    @DisplayName("at distance one, what a dig brings near is exactly what it reveals")
    void distanceOneIsReveal() {
        Random random = new Random(7);
        Rock rock = new Rock();
        for (int i = 0; i < 300; i++) {
            rock.air(random.nextInt(12), random.nextInt(12), random.nextInt(12));
        }
        RevealRule reveals = new RevealRule();
        for (int i = 0; i < 200; i++) {
            int x = 1 + random.nextInt(10);
            int y = 1 + random.nextInt(10);
            int z = 1 + random.nextInt(10);
            if (rock.open(x, y, z)) {
                continue;
            }
            Set<List<Integer>> revealed = new HashSet<>();
            reveals.revealedBy(rock, x, y, z).forEach(r -> revealed.add(List.of(r.x(), r.y(), r.z())));
            Set<List<Integer>> near = new HashSet<>();
            rule.newlyNear(rock, x, y, z, 1).forEach(c -> near.add(List.of(c[0], c[1], c[2])));
            assertThat(near).isEqualTo(revealed);
        }
    }

    @Test
    @DisplayName("one step of a straight tunnel brings 13 blocks within two of open space")
    void tunnelStep() {
        Rock rock = new Rock();
        for (int x = -10; x < 0; x++) {
            rock.air(x, 0, 0);
        }
        List<int[]> near = rule.newlyNear(rock, 0, 0, 0, 2);
        assertThat(near).hasSize(13);
        assertThat(near).allSatisfy(c -> assertThat(Math.abs(c[0]) + Math.abs(c[1]) + Math.abs(c[2])).isEqualTo(2));
    }
}
