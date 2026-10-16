package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.BentoIsland;
import de.raindancer.modules.oneblock.model.BlockSpot;
import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Spawn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationRuleTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private final MigrationRule rule = new MigrationRule();

    private static BentoIsland bento(long lifetime) {
        return new BentoIsland("AOneBlockx", OWNER, List.of(MEMBER), -800, 80, 800, 50, 400, lifetime,
                Optional.of(new Spawn(-797.5, 81, 803.5, 90f, 0f)), 1234L);
    }

    @Test
    @DisplayName("the block stays exactly where BentoBox had it, the team comes along, the money is not chosen yet")
    void sameSpot() {
        Island island = rule.island(Contents.content(), bento(100), "Lilly", new MigrationRule.Thresholds(7500, 11500),
                UUID.randomUUID());

        assertThat(island.block(IslandDimension.OVERWORLD).orElseThrow().spot()).isEqualTo(new BlockSpot(-800, 80, 800));
        assertThat(island.block(IslandDimension.OVERWORLD).orElseThrow().broken()).isEqualTo(100);
        assertThat(island.owner()).isEqualTo(OWNER);
        assertThat(island.members()).containsExactly(MEMBER);
        assertThat(island.mode()).isEqualTo(EconomyMode.UNDECIDED);
        assertThat(island.spawn(IslandDimension.OVERWORLD)).isEqualTo(new Spawn(-797.5, 81, 803.5, 90f, 0f));
        assertThat(island.created()).isEqualTo(1234L);
        assertThat(island.ownerName()).isEqualTo("Lilly");
    }

    @Test
    @DisplayName("shards are what the breaks would have earned here")
    void shards() {
        Island island = rule.island(Contents.content(), bento(2557), "L", new MigrationRule.Thresholds(7500, 11500),
                UUID.randomUUID());

        assertThat(island.shards()).isEqualTo(255);
    }

    @Test
    @DisplayName("a dimension AOneBlock had already reached comes unlocked")
    void unlocksByProgress() {
        MigrationRule.Thresholds at = new MigrationRule.Thresholds(7500, 11500);

        Island early = rule.island(Contents.content(), bento(7499), "L", at, UUID.randomUUID());
        Island nether = rule.island(Contents.content(), bento(7500), "L", at, UUID.randomUUID());
        Island end = rule.island(Contents.content(), bento(20000), "L", at, UUID.randomUUID());

        assertThat(early.unlockedDimensions()).containsExactly(IslandDimension.OVERWORLD);
        assertThat(nether.unlockedDimensions()).containsExactlyInAnyOrder(IslandDimension.OVERWORLD,
                IslandDimension.NETHER);
        assertThat(end.unlockedDimensions()).containsExactlyInAnyOrder(IslandDimension.OVERWORLD,
                IslandDimension.NETHER, IslandDimension.END);
        assertThat(end.block(IslandDimension.END).orElseThrow().spot()).isEqualTo(IslandDimension.END.start());
    }

    @Test
    @DisplayName("the region files are exactly those under the island's protected square")
    void regions() {
        assertThat(rule.regions(bento(0))).containsExactlyInAnyOrder("r.-2.1.mca");
        BentoIsland straddling = new BentoIsland("x", OWNER, List.of(), 0, 80, 0, 50, 400, 0, Optional.empty(), 0);
        assertThat(rule.regions(straddling)).containsExactlyInAnyOrder("r.-1.-1.mca", "r.-1.0.mca", "r.0.-1.mca",
                "r.0.0.mca");
    }

    @Test
    @DisplayName("islands 800 apart never share a region file, so nobody's island takes another's blocks along")
    void neighboursNeverShare() {
        for (int ax = -2; ax <= 2; ax++) {
            for (int az = -2; az <= 2; az++) {
                BentoIsland a = new BentoIsland("a", OWNER, List.of(), ax * 800, 80, az * 800, 50, 400, 0,
                        Optional.empty(), 0);
                BentoIsland b = new BentoIsland("b", OWNER, List.of(), ax * 800 + 800, 80, az * 800, 50, 400, 0,
                        Optional.empty(), 0);
                BentoIsland c = new BentoIsland("c", OWNER, List.of(), ax * 800, 80, az * 800 + 800, 50, 400, 0,
                        Optional.empty(), 0);
                assertThat(rule.regions(a)).doesNotContainAnyElementsOf(rule.regions(b))
                        .doesNotContainAnyElementsOf(rule.regions(c));
            }
        }
    }

    @Test
    @DisplayName("somebody who logged out on their island is put back on it; elsewhere they go home")
    void positions() {
        BentoIsland island = bento(0);

        assertThat(rule.covers(island, -810.2, 900)).isTrue();
        assertThat(rule.covers(island, -1201, 800)).isFalse();
        assertThat(rule.covers(island, 0, 0)).isFalse();
    }
}
