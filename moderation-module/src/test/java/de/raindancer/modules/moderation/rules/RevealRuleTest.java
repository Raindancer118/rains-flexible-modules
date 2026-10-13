package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.model.SeenOres;
import de.raindancer.modules.moderation.util.BlockGrid;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A vein that shows in a cave wall was found by looking, not by digging. Uncovering its hidden half —
 * from the cave side or by tunnelling around it — must not count as a new find.
 */
class RevealRuleTest {

    private final RevealRule rule = new RevealRule();

    /** Stone everywhere, apart from what a test sets. */
    private static final class Grid implements BlockGrid {
        final Map<List<Integer>, Material> blocks = new HashMap<>();

        Grid set(int x, int y, int z, Material type) {
            blocks.put(List.of(x, y, z), type);
            return this;
        }

        @Override
        public Material at(int x, int y, int z) {
            return blocks.getOrDefault(List.of(x, y, z), Material.STONE);
        }

        @Override
        public boolean open(int x, int y, int z) {
            return at(x, y, z) == Material.AIR;
        }
    }

    /** A player standing in the cell at the origin. */
    private static final double[] EYE = {0.5, 1.62, 0.5};

    private static boolean diamond(Material type) {
        return type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE;
    }

    private RevealRule.Revealed revealedAt(Grid grid, int bx, int by, int bz, int x, int y, int z) {
        return rule.revealedBy(grid, bx, by, bz).stream()
                .filter(r -> r.x() == x && r.y() == y && r.z() == z)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the hidden half of a vein showing in a cave is no new find")
    void caveVeinIsNoFind() {
        // Cave air at x = 0; diamond (1,0,0) shows in the wall, its vein carries on at (2,0,0) and (2,1,0).
        Grid grid = new Grid()
                .set(0, 0, 0, Material.AIR).set(0, 1, 0, Material.AIR)
                .set(1, 0, 0, Material.DIAMOND_ORE)
                .set(2, 0, 0, Material.DEEPSLATE_DIAMOND_ORE)
                .set(2, 1, 0, Material.DIAMOND_ORE);
        // The player first breaks the stone at (1,1,0), above the visible diamond: (2,1,0) is uncovered.
        RevealRule.Revealed shown = revealedAt(grid, 1, 1, 0, 2, 1, 0);

        assertThat(rule.newVein(grid, shown, new SeenOres(), RevealRuleTest::diamond, EYE)).isFalse();
    }

    @Test
    @DisplayName("a vein reached through solid rock is a new find")
    void hiddenVeinIsAFind() {
        Grid grid = new Grid()
                .set(0, 0, 0, Material.AIR)
                .set(2, 0, 0, Material.DIAMOND_ORE)
                .set(3, 0, 0, Material.DIAMOND_ORE);
        RevealRule.Revealed shown = revealedAt(grid, 1, 0, 0, 2, 0, 0);

        assertThat(rule.newVein(grid, shown, new SeenOres(), RevealRuleTest::diamond, EYE)).isTrue();
    }

    @Test
    @DisplayName("ore next to one already seen carries the same vein on")
    void seenVeinCarriesOn() {
        Grid grid = new Grid().set(0, 0, 0, Material.AIR).set(2, 0, 0, Material.DIAMOND_ORE);
        SeenOres seen = new SeenOres();
        seen.add(2, 1, 1);
        RevealRule.Revealed shown = revealedAt(grid, 1, 0, 0, 2, 0, 0);

        assertThat(rule.newVein(grid, shown, seen, RevealRuleTest::diamond, EYE)).isFalse();
    }

    @Test
    @DisplayName("other ore showing in a cave nearby does not excuse a hidden diamond")
    void otherOreInCaveIsNoExcuse() {
        Grid grid = new Grid()
                .set(0, 0, 0, Material.AIR).set(0, 1, 0, Material.AIR)
                .set(1, 0, 0, Material.IRON_ORE)
                .set(2, 1, 0, Material.DIAMOND_ORE);
        RevealRule.Revealed shown = revealedAt(grid, 1, 1, 0, 2, 1, 0);

        assertThat(rule.newVein(grid, shown, new SeenOres(), RevealRuleTest::diamond, EYE)).isTrue();
    }

    @Test
    @DisplayName("a vein that touches only a sealed pocket nobody can see into is a new find")
    void sealedPocketIsNoExcuse() {
        // Tunnel at the origin; (2,0,0) is uncovered, its vein carries on to (3,0,0), which touches a sealed
        // air pocket at (4,0,0) — open space, but only an x-ray shows it from the tunnel.
        Grid grid = new Grid()
                .set(0, 0, 0, Material.AIR).set(0, 1, 0, Material.AIR)
                .set(2, 0, 0, Material.DIAMOND_ORE)
                .set(3, 0, 0, Material.DIAMOND_ORE)
                .set(4, 0, 0, Material.AIR);
        RevealRule.Revealed shown = revealedAt(grid, 1, 0, 0, 2, 0, 0);

        assertThat(rule.newVein(grid, shown, new SeenOres(), RevealRuleTest::diamond, EYE)).isTrue();
    }

    @Test
    @DisplayName("a vein seen across a big cave still counts as seen")
    void seenAcrossTheCave() {
        Grid grid = new Grid();
        for (int x = 0; x < 30; x++) {
            grid.set(x, 0, 0, Material.AIR).set(x, 1, 0, Material.AIR);
        }
        grid.set(30, 0, 0, Material.DIAMOND_ORE).set(31, 1, 0, Material.DIAMOND_ORE);
        // Seen from the far end, they break the stone above the visible diamond first.
        RevealRule.Revealed shown = revealedAt(grid, 30, 1, 0, 31, 1, 0);

        assertThat(rule.newVein(grid, shown, new SeenOres(), RevealRuleTest::diamond, EYE)).isFalse();
    }

    @Test
    @DisplayName("a huge connected blob is not walked forever")
    void boundedWalk() {
        Grid grid = new Grid().set(-1, 0, 0, Material.AIR);
        for (int x = 1; x < 200; x++) {
            grid.set(x, 0, 0, Material.DIAMOND_ORE);
        }
        grid.set(200, 1, 0, Material.AIR);
        RevealRule.Revealed shown = revealedAt(grid, 0, 0, 0, 1, 0, 0);

        assertThat(rule.newVein(grid, shown, new SeenOres(), RevealRuleTest::diamond, EYE)).isTrue();
    }

    @Test
    @DisplayName("a placed blob behind a wall at the end of a big cave costs bounded work per dig")
    void boundedWork() {
        // Cave for x < 50, a two-thick wall, then a 4x4x4 blob of diamond ore wrapped in air pockets: every
        // face is open, none can be seen, and each sight line crosses 50 blocks of air before it is blocked.
        int[] reads = {0};
        BlockGrid grid = new BlockGrid() {
            @Override
            public Material at(int x, int y, int z) {
                reads[0]++;
                if (x < 50) {
                    return Material.AIR;
                }
                if (x <= 51) {
                    return Material.STONE;
                }
                boolean blob = x <= 55 && y >= 0 && y <= 3 && z >= 0 && z <= 3;
                if (blob) {
                    return Material.DIAMOND_ORE;
                }
                boolean shell = x <= 56 && y >= -1 && y <= 4 && z >= -1 && z <= 4;
                return shell ? Material.AIR : Material.STONE;
            }

            @Override
            public boolean open(int x, int y, int z) {
                return at(x, y, z) == Material.AIR;
            }
        };
        RevealRule.Revealed shown = new RevealRule.Revealed(53, 1, 1, Material.DIAMOND_ORE);

        assertThat(rule.newVein(grid, shown, new SeenOres(), RevealRuleTest::diamond, new double[]{0.5, 2.5, 2.5})).isTrue();
        assertThat(reads[0]).isLessThan(15_000);
    }
}
