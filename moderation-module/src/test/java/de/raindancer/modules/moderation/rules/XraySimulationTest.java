package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.util.BlockGrid;

import de.raindancer.modules.moderation.model.DigPath;
import de.raindancer.modules.moderation.model.MiningLedger;
import de.raindancer.modules.moderation.model.OreDensity;
import de.raindancer.modules.moderation.model.OreKind;
import de.raindancer.modules.moderation.model.RockBand;
import de.raindancer.modules.moderation.model.SeenOres;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole method on a made-up world: stone with diamond veins at a realistic density, one honest
 * branch-miner and one miner who tunnels straight to the nearest hidden diamond. Both go through the
 * same reveal, density, steering and verdict code the server runs.
 */
class XraySimulationTest {

    private static final int SIZE_X = 160;
    private static final int SIZE_Y = 16;
    private static final int SIZE_Z = 160;
    private static final RockBand BAND = RockBand.of("NORMAL", 8);

    private final RevealRule reveals = new RevealRule();
    private final SteeringRule steering = new SteeringRule();
    private final XrayVerdictRule verdicts = new XrayVerdictRule();
    private final HiddenOreRule search = new HiddenOreRule();

    /** A world of stone with veins of 1–5 diamonds, about one ore block in 600. */
    private static final class World implements BlockGrid {
        final Material[][][] blocks = new Material[SIZE_X][SIZE_Y][SIZE_Z];

        World(long seed) {
            Random random = new Random(seed);
            for (Material[][] plane : blocks) {
                for (Material[] row : plane) {
                    java.util.Arrays.fill(row, Material.STONE);
                }
            }
            int veins = SIZE_X * SIZE_Y * SIZE_Z / 1500;
            for (int vein = 0; vein < veins; vein++) {
                int x = 2 + random.nextInt(SIZE_X - 4);
                int y = 2 + random.nextInt(SIZE_Y - 4);
                int z = 2 + random.nextInt(SIZE_Z - 4);
                int size = 1 + random.nextInt(5);
                for (int i = 0; i < size; i++) {
                    blocks[x][y][z] = Material.DIAMOND_ORE;
                    switch (random.nextInt(3)) {
                        case 0 -> x = Math.max(1, Math.min(SIZE_X - 2, x + (random.nextBoolean() ? 1 : -1)));
                        case 1 -> y = Math.max(1, Math.min(SIZE_Y - 2, y + (random.nextBoolean() ? 1 : -1)));
                        default -> z = Math.max(1, Math.min(SIZE_Z - 2, z + (random.nextBoolean() ? 1 : -1)));
                    }
                }
            }
        }

        @Override
        public boolean open(int x, int y, int z) {
            return at(x, y, z) == Material.AIR;
        }

        @Override
        public Material at(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= SIZE_X || y >= SIZE_Y || z >= SIZE_Z) {
                return Material.BEDROCK;
            }
            return blocks[x][y][z];
        }

        OreDensity sample() {
            OreDensity density = new OreDensity();
            long enclosed = 0;
            long diamonds = 0;
            for (int x = 1; x < SIZE_X - 1; x++) {
                for (int y = 1; y < SIZE_Y - 1; y++) {
                    for (int z = 1; z < SIZE_Z - 1; z++) {
                        if (RevealRule.enclosed(this, x, y, z)) {
                            enclosed++;
                            if (blocks[x][y][z] == Material.DIAMOND_ORE) {
                                diamonds++;
                            }
                        }
                    }
                }
            }
            Map<OreKind, Long> ores = new EnumMap<>(OreKind.class);
            ores.put(OreKind.DIAMOND, diamonds);
            density.add(BAND, enclosed, ores);
            return density;
        }
    }

    /** One miner digging through the world, keeping a ledger the way the listener does. */
    private final class Miner {
        final World world;
        final MiningLedger ledger = new MiningLedger(0);
        final DigPath path = new DigPath();
        final SeenOres seen = new SeenOres();
        int mined;

        Miner(World world) {
            this.world = world;
        }

        void dig(int x, int y, int z) {
            if (world.at(x, y, z) == Material.AIR || world.at(x, y, z) == Material.BEDROCK) {
                return;
            }
            boolean ore = world.at(x, y, z) == Material.DIAMOND_ORE;
            if (ore) {
                seen.add(x, y, z);
            }
            List<RevealRule.Revealed> shown = reveals.revealedBy(world, x, y, z);
            ledger.revealed(BAND, shown.size());
            for (RevealRule.Revealed block : shown) {
                if (block.type() == Material.DIAMOND_ORE) {
                    if (!seen.touches(block.x(), block.y(), block.z())) {
                        ledger.vein(BAND, OreKind.DIAMOND);
                    }
                    seen.add(block.x(), block.y(), block.z());
                }
            }
            java.util.Optional<DigPath.Bend> bend = ore ? java.util.Optional.empty() : path.dug(x, y, z);
            bend.ifPresent(turn -> {
                HiddenOreRule.Census census = search.around(world, turn.at(), 8,
                        type -> type == Material.DIAMOND_ORE, type -> type == Material.STONE);
                SteeringRule.Pair pair = steering.judgeCensus(turn.before(), turn.after(), turn.at(), census.ores(), census.rocks());
                if (pair != SteeringRule.Pair.NO_DIFFERENCE) {
                    ledger.turn(pair == SteeringRule.Pair.ORE_ONLY);
                }
            });
            world.blocks[x][y][z] = Material.AIR;
            mined++;
        }

        /** Honest miners dig out any diamond they see in their walls. */
        void mineVisibleOreAround(int x, int y, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int ox = x + dx;
                        int oy = y + dy;
                        int oz = z + dz;
                        if (world.at(ox, oy, oz) == Material.DIAMOND_ORE && !RevealRule.enclosed(world, ox, oy, oz)) {
                            dig(ox, oy, oz);
                        }
                    }
                }
            }
        }

    }

    /** What an x-ray shows: the nearest diamond, wherever it hides. */
    private static int[] nearestHidden(Miner miner, int[] from, int radius) {
        int[] best = null;
        long bestDistance = Long.MAX_VALUE;
        for (int x = from[0] - radius; x <= from[0] + radius; x++) {
            for (int y = from[1] - radius; y <= from[1] + radius; y++) {
                for (int z = from[2] - radius; z <= from[2] + radius; z++) {
                    if (miner.world.at(x, y, z) != Material.DIAMOND_ORE || miner.seen.contains(x, y, z)) {
                        continue;
                    }
                    long distance = (long) (x - from[0]) * (x - from[0]) + (long) (y - from[1]) * (y - from[1])
                            + (long) (z - from[2]) * (z - from[2]);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        return best;
    }

    private static long key(int x, int y, int z) {
        return ((long) x << 40) | ((long) y << 20) | z;
    }

    /** Branch mining: two-high tunnels, three blocks apart, across the whole world. */
    private Miner honest(World world) {
        Miner miner = new Miner(world);
        int y = 7;
        for (int z = 4; z < SIZE_Z - 4; z += 4) {
            boolean east = (z / 4) % 2 == 0;
            for (int i = 2; i < SIZE_X - 2; i++) {
                int x = east ? i : SIZE_X - 1 - i;
                miner.dig(x, y, z);
                miner.dig(x, y + 1, z);
                miner.mineVisibleOreAround(x, y, z);
            }
            for (int step = 1; step < 4 && z + step < SIZE_Z - 4; step++) {
                int x = east ? SIZE_X - 3 : 2;
                miner.dig(x, y, z + step);
                miner.dig(x, y + 1, z + step);
            }
        }
        return miner;
    }

    /** X-ray: tunnel straight at the nearest diamond nobody could see, dig it out, repeat. */
    private Miner cheater(World world, int blocks) {
        Miner miner = new Miner(world);
        int x = SIZE_X / 2;
        int y = 7;
        int z = SIZE_Z / 2;
        while (miner.mined < blocks) {
            int[] target = nearestHidden(miner, new int[]{x, y, z}, 24);
            if (target == null) {
                break;
            }
            while (x != target[0] || y != target[1] || z != target[2]) {
                if (x != target[0]) {
                    x += Integer.signum(target[0] - x);
                } else if (z != target[2]) {
                    z += Integer.signum(target[2] - z);
                } else {
                    y += Integer.signum(target[1] - y);
                }
                miner.dig(x, y, z);
                miner.dig(x, y + 1, z);
            }
            miner.mineVisibleOreAround(x, y, z);
        }
        return miner;
    }

    @Test
    @DisplayName("an honest branch-miner stays unremarkable over several worlds")
    void honestStaysClean() {
        for (long seed = 1; seed <= 6; seed++) {
            World world = new World(seed);
            OreDensity density = world.sample();
            Miner miner = honest(world);
            XrayVerdictRule.Verdict verdict = verdicts.judge(miner.ledger, density, List.of(OreKind.DIAMOND));
            assertThat(miner.mined).isGreaterThan(5000);
            assertThat(verdict.score()).as("seed %d: %s", seed, verdict.signals()).isLessThan(3);
        }
    }

    @Test
    @DisplayName("an x-ray miner is one in a million or worse, after fewer blocks than the honest one digs")
    void cheaterIsCaught() {
        for (long seed = 1; seed <= 6; seed++) {
            World world = new World(seed);
            OreDensity density = world.sample();
            Miner miner = cheater(world, 1500);
            XrayVerdictRule.Verdict verdict = verdicts.judge(miner.ledger, density, List.of(OreKind.DIAMOND));
            assertThat(verdict.score()).as("seed %d: %s", seed, verdict.signals()).isGreaterThan(6);
        }
    }

    @Test
    @DisplayName("each test says what it found in a sentence a moderator can read")
    void signalsExplainThemselves() {
        World world = new World(9);
        OreDensity density = world.sample();
        XrayVerdictRule.Verdict verdict = verdicts.judge(cheater(world, 1500).ledger, density, List.of(OreKind.DIAMOND));
        assertThat(verdict.signals()).extracting(XrayVerdictRule.Signal::name).contains("Ore found", "Steering");
        assertThat(verdict.signals()).allSatisfy(signal -> assertThat(signal.summary()).isNotBlank());
        assertThat(verdict.percent()).isGreaterThanOrEqualTo(99);
    }

    @Test
    @DisplayName("bait reached far beyond chance is damning on its own; bait at chance is not")
    void bait() {
        MiningLedger ledger = new MiningLedger(0);
        ledger.bait(0.4, 6);
        assertThat(verdicts.judge(ledger, new OreDensity(), List.of()).score()).isGreaterThan(5);
        MiningLedger lucky = new MiningLedger(0);
        lucky.bait(0.4, 1);
        assertThat(verdicts.judge(lucky, new OreDensity(), List.of()).score()).isLessThan(1);
    }

    @Test
    @DisplayName("a ledger fades with its half-life")
    void decay() {
        MiningLedger ledger = new MiningLedger(0);
        ledger.revealed(BAND, 100);
        ledger.bait(1, 4);
        ledger.decay(1000, 1000);
        assertThat(ledger.totalRevealed()).isEqualTo(50);
        assertThat(ledger.baitReached()).isEqualTo(2);
    }
}
