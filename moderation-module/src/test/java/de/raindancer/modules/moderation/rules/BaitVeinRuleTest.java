package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.util.BlockGrid;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bait ores as a fixed layer of veins in the world, like real ore — not single blocks that follow a
 * miner around and pop in and out of an x-ray's view.
 */
class BaitVeinRuleTest {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final BaitVeinRule layer = new BaitVeinRule();

    @Test
    @DisplayName("the same chunk always holds the same veins; another server seed holds others")
    void deterministic() {
        List<BaitVeinRule.Vein> first = layer.veinsIn(42, WORLD, "NORMAL", -64, 3, -7, 30);
        List<BaitVeinRule.Vein> again = layer.veinsIn(42, WORLD, "NORMAL", -64, 3, -7, 30);
        List<BaitVeinRule.Vein> other = layer.veinsIn(43, WORLD, "NORMAL", -64, 3, -7, 30);

        assertThat(again).usingRecursiveFieldByFieldElementComparator().isEqualTo(first);
        assertThat(other).usingRecursiveFieldByFieldElementComparator().isNotEqualTo(first);
    }

    @Test
    @DisplayName("veins are anchored in their chunk and band, connected, and no bigger than real ones")
    void veinShapes() {
        Set<Integer> diamondSizes = new HashSet<>();
        int veins = 0;
        for (int cx = -20; cx < 20; cx++) {
            for (int cz = -20; cz < 20; cz++) {
                for (BaitVeinRule.Vein vein : layer.veinsIn(7, WORLD, "NORMAL", -64, cx, cz, 15)) {
                    veins++;
                    assertThat(vein.x() >> 4).isEqualTo(cx);
                    assertThat(vein.z() >> 4).isEqualTo(cz);
                    assertThat(vein.y()).isBetween(-63, 39);
                    assertThat(vein.kind()).isEqualTo(vein.y() < 16 ? BaitVeinRule.Kind.DIAMOND : BaitVeinRule.Kind.GOLD);
                    assertConnected(vein.blocks());
                    for (int[] block : vein.blocks()) {
                        int steps = Math.abs(block[0] - vein.x()) + Math.abs(block[1] - vein.y()) + Math.abs(block[2] - vein.z());
                        assertThat(steps).isLessThanOrEqualTo(BaitVeinRule.EXTENT);
                    }
                    if (vein.kind() == BaitVeinRule.Kind.DIAMOND) {
                        diamondSizes.add(vein.blocks().length);
                    }
                }
            }
        }
        assertThat((double) veins / 1600).isBetween(1.3, 1.7);
        assertThat(diamondSizes).contains(1, 2, 3, 4).allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(8));
    }

    @Test
    @DisplayName("nether veins are ancient debris of one to three blocks")
    void nether() {
        for (int cx = 0; cx < 30; cx++) {
            for (BaitVeinRule.Vein vein : layer.veinsIn(9, WORLD, "NETHER", 0, cx, 0, 20)) {
                assertThat(vein.kind()).isEqualTo(BaitVeinRule.Kind.DEBRIS);
                assertThat(vein.blocks().length).isBetween(1, 3);
                assertThat(vein.y()).isBetween(8, 119);
            }
        }
        assertThat(layer.veinsIn(9, WORLD, "THE_END", 0, 0, 0, 100)).isEmpty();
    }

    @Test
    @DisplayName("each fake block matches the rock it sits in")
    void fakeMatchesHost() {
        assertThat(BaitVeinRule.fakeFor(BaitVeinRule.Kind.DIAMOND, Material.DEEPSLATE)).isEqualTo(Material.DEEPSLATE_DIAMOND_ORE);
        assertThat(BaitVeinRule.fakeFor(BaitVeinRule.Kind.DIAMOND, Material.STONE)).isEqualTo(Material.DIAMOND_ORE);
        assertThat(BaitVeinRule.fakeFor(BaitVeinRule.Kind.GOLD, Material.TUFF)).isEqualTo(Material.DEEPSLATE_GOLD_ORE);
        assertThat(BaitVeinRule.fakeFor(BaitVeinRule.Kind.DEBRIS, Material.NETHERRACK)).isEqualTo(Material.ANCIENT_DEBRIS);
        assertThat(BaitVeinRule.fakeFor(BaitVeinRule.Kind.DEBRIS, Material.STONE)).isNull();
        assertThat(BaitVeinRule.fakeFor(BaitVeinRule.Kind.DIAMOND, Material.NETHERRACK)).isNull();
    }

    @Test
    @DisplayName("a vein is shown only in its own rock, beyond sight distance of open space, touching no ore")
    void fits() {
        Cave world = new Cave(16, new Random(1));
        BaitVeinRule.Vein vein = new BaitVeinRule.Vein(8, 8, 8, BaitVeinRule.Kind.DIAMOND, new int[][]{{8, 8, 8}, {9, 8, 8}});
        assertThat(layer.fits(world, vein, 3, Cave::ore)).isTrue();

        world.set(12, 8, 8, Material.AIR);
        assertThat(layer.fits(world, vein, 3, Cave::ore)).as("open space three steps from a block").isFalse();
        assertThat(layer.fits(world, vein, 2, Cave::ore)).isTrue();

        world.set(10, 9, 8, Material.IRON_ORE);
        assertThat(layer.fits(world, vein, 2, Cave::ore)).as("an ore touching it diagonally").isFalse();
    }

    /**
     * The test that matters: an honest miner tunnels blind through rock full of caves and bait veins.
     * The number of veins they come near must be what {@link BaitVeinRule#expected} says chance gives —
     * never more, or honest miners would look like x-rayers.
     */
    @Test
    @DisplayName("an honest miner reaches about as many veins as expected, at sight distance 1 and 4")
    void calibrated() {
        for (int distance : new int[]{1, 4}) {
            double expected = 0;
            int reached = 0;
            for (int run = 0; run < 12; run++) {
                Random random = new Random(run * 31L + distance);
                Cave world = new Cave(48, random);
                List<BaitVeinRule.Vein> live = new ArrayList<>();
                Set<String> taken = new HashSet<>();
                for (int cx = 0; cx < 3; cx++) {
                    for (int cz = 0; cz < 3; cz++) {
                        for (BaitVeinRule.Vein vein : layer.veinsIn(run, WORLD, "NORMAL", Cave.MIN_Y, cx, cz, Cave.DENSITY)) {
                            if (layer.fits(world, vein, distance, Cave::ore) && untaken(vein, taken)) {
                                live.add(vein);
                            }
                        }
                    }
                }
                int[] at = world.start(random);
                final int[] start = at;
                live.removeIf(vein -> near(vein, start, distance));
                int[] direction = {1, 0, 0};
                for (int dig = 0; dig < 1500; dig++) {
                    if (random.nextInt(8) == 0) {
                        direction = Cave.DIRECTIONS[random.nextInt(Cave.DIRECTIONS.length)];
                    }
                    int[] next = {at[0] + direction[0], at[1] + direction[1], at[2] + direction[2]};
                    if (!world.inside(next, 4)) {
                        direction = Cave.DIRECTIONS[random.nextInt(Cave.DIRECTIONS.length)];
                        continue;
                    }
                    if (!world.open(next[0], next[1], next[2])) {
                        expected += layer.expected(world, "NORMAL", Cave.MIN_Y, Cave.DENSITY, next[0], next[1], next[2], distance);
                        final int[] x = next;
                        reached += (int) live.stream().filter(vein -> near(vein, x, distance)).count();
                        live.removeIf(vein -> near(vein, x, distance));
                        world.set(next[0], next[1], next[2], Material.AIR);
                    }
                    at = next;
                }
            }
            assertThat(reached).as("enough veins reached to judge, distance %d", distance).isGreaterThan(80);
            assertThat(reached / expected).as("reached against expected, distance %d (%d vs %.1f)", distance, reached, expected)
                    .isBetween(0.75, 1.08);
        }
    }

    private static boolean near(BaitVeinRule.Vein vein, int[] at, int distance) {
        for (int[] block : vein.blocks()) {
            if (Math.abs(block[0] - at[0]) + Math.abs(block[1] - at[1]) + Math.abs(block[2] - at[2]) <= distance) {
                return true;
            }
        }
        return false;
    }

    private static boolean untaken(BaitVeinRule.Vein vein, Set<String> taken) {
        for (int[] block : vein.blocks()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (taken.contains((block[0] + dx) + "," + (block[1] + dy) + "," + (block[2] + dz))) {
                            return false;
                        }
                    }
                }
            }
        }
        for (int[] block : vein.blocks()) {
            taken.add(block[0] + "," + block[1] + "," + block[2]);
        }
        return true;
    }

    private static void assertConnected(int[][] blocks) {
        Set<String> all = new HashSet<>();
        for (int[] block : blocks) {
            all.add(block[0] + "," + block[1] + "," + block[2]);
        }
        assertThat(all).as("no block twice").hasSize(blocks.length);
        Set<String> seen = new HashSet<>();
        List<int[]> todo = new ArrayList<>(List.of(blocks[0]));
        while (!todo.isEmpty()) {
            int[] block = todo.remove(todo.size() - 1);
            if (!seen.add(block[0] + "," + block[1] + "," + block[2])) {
                continue;
            }
            for (int[] other : blocks) {
                int dx = Math.abs(other[0] - block[0]);
                int dy = Math.abs(other[1] - block[1]);
                int dz = Math.abs(other[2] - block[2]);
                if (Math.max(dx, Math.max(dy, dz)) == 1 && dx + dy + dz <= 2) {
                    todo.add(other);
                }
            }
        }
        assertThat(seen).as("one connected vein").hasSize(blocks.length);
    }

    /** Stone and deepslate around y = 0 with round caves; everything outside is bedrock. */
    private static final class Cave implements BlockGrid {
        static final int MIN_Y = -64;
        static final int DENSITY = 400;
        static final int[][] DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        final int size;
        final Material[][][] blocks;

        Cave(int size, Random random) {
            this.size = size;
            blocks = new Material[size][size][size];
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    for (int z = 0; z < size; z++) {
                        blocks[x][y][z] = y + offset() < 0 ? Material.DEEPSLATE : Material.STONE;
                    }
                }
            }
            for (int cave = 0; cave < (size > 16 ? size / 8 : 0); cave++) {
                int cx = random.nextInt(size);
                int cy = random.nextInt(size);
                int cz = random.nextInt(size);
                int r = 2 + random.nextInt(3);
                for (int x = cx - r; x <= cx + r; x++) {
                    for (int y = cy - r; y <= cy + r; y++) {
                        for (int z = cz - r; z <= cz + r; z++) {
                            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz) <= r * r) {
                                set(x, y - offset(), z, Material.AIR);
                            }
                        }
                    }
                }
            }
        }

        /** World height of the array's bottom layer, so the band crosses y = 16 from diamond into gold. */
        int offset() {
            return size > 16 ? -24 : 0;
        }

        static boolean ore(Material type) {
            return type.name().endsWith("_ORE");
        }

        void set(int x, int y, int z, Material type) {
            int iy = y - offset();
            if (x >= 0 && iy >= 0 && z >= 0 && x < size && iy < size && z < size) {
                blocks[x][iy][z] = type;
            }
        }

        boolean inside(int[] at, int margin) {
            int iy = at[1] - offset();
            return at[0] >= margin && iy >= margin && at[2] >= margin
                    && at[0] < size - margin && iy < size - margin && at[2] < size - margin;
        }

        int[] start(Random random) {
            int[] at = {size / 2, size / 2 + offset(), size / 2};
            set(at[0], at[1], at[2], Material.AIR);
            return at;
        }

        @Override
        public Material at(int x, int y, int z) {
            int iy = y - offset();
            if (x < 0 || iy < 0 || z < 0 || x >= size || iy >= size || z >= size) {
                return Material.BEDROCK;
            }
            return blocks[x][iy][z];
        }

        @Override
        public boolean open(int x, int y, int z) {
            return at(x, y, z) == Material.AIR;
        }
    }
}
