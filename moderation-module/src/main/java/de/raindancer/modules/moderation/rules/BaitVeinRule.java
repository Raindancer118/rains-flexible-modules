package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.util.BlockGrid;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Where the bait veins are: a fixed layer of fake ore, worked out per chunk from a secret server seed,
 * the way the game places real ore. A vein is there when the chunk arrives, still there when the miner
 * comes back, the same for every player, and as big as real veins are — nothing an x-ray user can tell
 * from the real thing by how it looks or behaves, short of digging to it.
 *
 * <p>Which veins a player is actually shown is decided on the server, against the world: only veins
 * whose every block sits in fitting rock beyond the sight distance of open space ({@link #fits}).
 */
public final class BaitVeinRule implements IModerationRule {

    /** No vein block is farther than this many steps from its anchor; keeps veins compact and the work bounded. */
    public static final int EXTENT = 2;
    /** Shapes per kind the expectation averages over — drawn the same way the veins are. */
    private static final int SAMPLES = 24;
    private static final int[][] TOUCHING = touching();

    public enum Kind {
        /** Small blobs, like the game's size-4 diamond veins. */
        DIAMOND(new double[]{0, 0.15, 0.25, 0.25, 0.2, 0.1, 0.05}),
        /** Bigger blobs, like the game's size-9 gold veins. */
        GOLD(new double[]{0, 0, 0, 0.1, 0.2, 0.25, 0.2, 0.15, 0.1}),
        /** One to three blocks, as ancient debris comes. */
        DEBRIS(new double[]{0, 0.6, 0.3, 0.1});

        /** Chance of each size, by index. */
        final double[] sizes;
        final List<int[][]> samples = new ArrayList<>();

        Kind(double[] sizes) {
            this.sizes = sizes;
        }
    }

    static {
        SplittableRandom random = new SplittableRandom(0x5EED_BA17L);
        for (Kind kind : Kind.values()) {
            for (int i = 0; i < SAMPLES; i++) {
                kind.samples.add(shape(kind, random));
            }
        }
    }

    /** A vein: its anchor, kind, and the absolute positions of its blocks (the anchor first). */
    public record Vein(int x, int y, int z, Kind kind, int[][] blocks) {
    }

    /** Heights a vein's anchor can have, {low, high} with high exclusive, or null where there are none. */
    public static int[] band(String environment, int minY) {
        return switch (environment) {
            case "NORMAL" -> new int[]{minY + 1, 40};
            case "NETHER" -> new int[]{8, 120};
            default -> null;
        };
    }

    /** What would tempt an x-ray at this height: diamonds deep down, gold higher up, debris in the nether. */
    public static Kind kindAt(String environment, int y) {
        return switch (environment) {
            case "NORMAL" -> y < 16 ? Kind.DIAMOND : y < 40 ? Kind.GOLD : null;
            case "NETHER" -> y >= 8 && y < 120 ? Kind.DEBRIS : null;
            default -> null;
        };
    }

    /** The fake block for this kind in this rock, or null if the kind never sits in it. */
    public static Material fakeFor(Kind kind, Material host) {
        if (host == null) {
            return null;
        }
        boolean deep = host == Material.DEEPSLATE || host == Material.TUFF || host == Material.COBBLED_DEEPSLATE;
        return switch (kind) {
            case DIAMOND -> overworldRock(host) ? (deep ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE) : null;
            case GOLD -> overworldRock(host) ? (deep ? Material.DEEPSLATE_GOLD_ORE : Material.GOLD_ORE) : null;
            case DEBRIS -> host == Material.NETHERRACK || host == Material.BASALT || host == Material.BLACKSTONE
                    ? Material.ANCIENT_DEBRIS : null;
        };
    }

    private static boolean overworldRock(Material host) {
        return switch (host) {
            case STONE, DEEPSLATE, TUFF, GRANITE, DIORITE, ANDESITE, COBBLED_DEEPSLATE -> true;
            default -> false;
        };
    }

    /** Expected anchors per block of the band: the veins per chunk spread over its height. */
    public static double anchorDensity(String environment, int minY, int veinsPerTenChunks) {
        int[] band = band(environment, minY);
        if (band == null || band[1] <= band[0]) {
            return 0;
        }
        return veinsPerTenChunks / 10.0 / (256.0 * (band[1] - band[0]));
    }

    /** The veins of one chunk — always the same for the same seed, world and chunk. */
    public List<Vein> veinsIn(long seed, UUID world, String environment, int minY, int chunkX, int chunkZ,
                              int veinsPerTenChunks) {
        int[] band = band(environment, minY);
        if (band == null || band[1] <= band[0] || veinsPerTenChunks <= 0) {
            return List.of();
        }
        SplittableRandom random = new SplittableRandom(seed
                ^ world.getMostSignificantBits() * 0x9E3779B97F4A7C15L
                ^ world.getLeastSignificantBits() * 0xC2B2AE3D27D4EB4FL
                ^ chunkX * 0x165667B19E3779F9L
                ^ chunkZ * 0x27D4EB2F165667C5L);
        double perChunk = veinsPerTenChunks / 10.0;
        int count = (int) perChunk + (random.nextDouble() < perChunk - (int) perChunk ? 1 : 0);
        List<Vein> veins = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int x = (chunkX << 4) + random.nextInt(16);
            int z = (chunkZ << 4) + random.nextInt(16);
            int y = band[0] + random.nextInt(band[1] - band[0]);
            Kind kind = kindAt(environment, y);
            int[][] shape = shape(kind, random);
            int[][] blocks = new int[shape.length][];
            for (int b = 0; b < shape.length; b++) {
                blocks[b] = new int[]{x + shape[b][0], y + shape[b][1], z + shape[b][2]};
            }
            veins.add(new Vein(x, y, z, kind, blocks));
        }
        return veins;
    }

    /** A compact blob of this kind's size: offsets from the anchor, the anchor first, each touching another. */
    static int[][] shape(Kind kind, SplittableRandom random) {
        double roll = random.nextDouble();
        int size = kind.sizes.length - 1;
        for (int s = 0; s < kind.sizes.length; s++) {
            roll -= kind.sizes[s];
            if (roll < 0) {
                size = s;
                break;
            }
        }
        List<int[]> blocks = new ArrayList<>(List.of(new int[]{0, 0, 0}));
        while (blocks.size() < size) {
            int[] from = blocks.get(random.nextInt(blocks.size()));
            int[] step = TOUCHING[random.nextInt(TOUCHING.length)];
            int[] next = {from[0] + step[0], from[1] + step[1], from[2] + step[2]};
            if (Math.abs(next[0]) + Math.abs(next[1]) + Math.abs(next[2]) > EXTENT || contains(blocks, next)) {
                continue;
            }
            blocks.add(next);
        }
        return blocks.toArray(new int[0][]);
    }

    private static boolean contains(List<int[]> blocks, int[] block) {
        for (int[] other : blocks) {
            if (other[0] == block[0] && other[1] == block[1] && other[2] == block[2]) {
                return true;
            }
        }
        return false;
    }

    /** Face and edge neighbours: how ore blobs in the game hang together. */
    private static int[][] touching() {
        List<int[]> steps = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int sum = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (sum == 1 || sum == 2) {
                        steps.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }
        return steps.toArray(new int[0][]);
    }

    /**
     * Whether this vein may be shown: every block in rock its kind sits in, farther than the sight
     * distance from open space, and no real ore touching it — a bait never runs into a real vein.
     */
    public boolean fits(BlockGrid grid, Vein vein, int distance, Predicate<Material> ore) {
        for (int[] block : vein.blocks()) {
            if (fakeFor(vein.kind(), grid.at(block[0], block[1], block[2])) == null
                    || BaitSightRule.openDistance(grid, block[0], block[1], block[2], distance) <= distance) {
                return false;
            }
        }
        for (int[] block : vein.blocks()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (ore.test(grid.at(block[0] + dx, block[1] + dy, block[2] + dz))) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * How many bait veins chance alone brings within the sight distance when {@code (x, y, z)} is dug —
     * every vein position whose blocks would all have been eligible, that this dig is first to come
     * near, weighed by how likely a vein is anchored there. Called while the block still stands.
     *
     * <p>Veins that could not be shown for other reasons (a real ore touching, another bait in the
     * way) only make the true chance smaller, so this never understates it for an honest miner.
     */
    public double expected(BlockGrid grid, String environment, int minY, int veinsPerTenChunks,
                           int x, int y, int z, int distance) {
        double density = anchorDensity(environment, minY, veinsPerTenChunks);
        int[] band = band(environment, minY);
        if (density <= 0 || band == null) {
            return 0;
        }
        BaitSightRule.Field field = new BaitSightRule().field(grid, x, y, z, distance, EXTENT);
        double[] total = {0};
        for (Kind kind : Kind.values()) {
            if ((kind == Kind.DEBRIS) != environment.equals("NETHER")) {
                continue;
            }
            double each = density / kind.samples.size();
            for (int[][] shape : kind.samples) {
                field.anchors(shape, host -> fakeFor(kind, host) != null, anchorY -> {
                    if (anchorY >= band[0] && anchorY < band[1] && kindAt(environment, anchorY) == kind) {
                        total[0] += each;
                    }
                });
            }
        }
        return total[0];
    }

    @Override
    public String describe() {
        return "where the bait veins lie and how often chance alone comes near one";
    }
}
