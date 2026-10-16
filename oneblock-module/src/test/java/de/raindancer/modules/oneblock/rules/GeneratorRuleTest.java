package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.BlockSpot;
import de.raindancer.modules.oneblock.model.IslandBlock;
import de.raindancer.modules.oneblock.model.IslandDimension;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratorRuleTest {

    private final GeneratorRule rule = new GeneratorRule();
    private final IslandBlock bare = IslandBlock.fresh(BlockSpot.START);

    @Test
    @DisplayName("without upgrades the pool decides by weight")
    void poolByWeight() {
        assertThat(rule.next(Contents.content(), IslandDimension.OVERWORLD, bare, 99.9, 0.0).material())
                .isEqualTo(Material.DIRT);
        assertThat(rule.next(Contents.content(), IslandDimension.OVERWORLD, bare, 99.9, 0.74).material())
                .isEqualTo(Material.DIRT);
        assertThat(rule.next(Contents.content(), IslandDimension.OVERWORLD, bare, 99.9, 0.76).material())
                .isEqualTo(Material.OAK_LOG);
        assertThat(rule.next(Contents.content(), IslandDimension.OVERWORLD, bare, 0.0, 0.99).material())
                .isEqualTo(Material.OAK_LOG);
    }

    @Test
    @DisplayName("installed upgrades share one roll, so their chances add up instead of compounding")
    void upgradesAddUp() {
        IslandBlock block = bare.withLevel("coal", 2).withLevel("cow", 1);

        assertThat(rule.next(Contents.content(), IslandDimension.OVERWORLD, block, 0.0, 0.0).material())
                .isEqualTo(Material.COAL_ORE);
        assertThat(rule.next(Contents.content(), IslandDimension.OVERWORLD, block, 9.99, 0.0).material())
                .isEqualTo(Material.COAL_ORE);
        GeneratorRule.Outcome cow = rule.next(Contents.content(), IslandDimension.OVERWORLD, block, 10.0, 0.0);
        assertThat(cow.mob()).isEqualTo(EntityType.COW);
        assertThat(cow.material()).as("a mob leaves the block to the pool").isEqualTo(Material.DIRT);
        assertThat(rule.next(Contents.content(), IslandDimension.OVERWORLD, block, 15.0, 0.0).mob()).isNull();
    }

    @Test
    @DisplayName("specials, unknown ids and upgrades of another dimension never take part in the roll")
    void onlyRollingUpgradesOfThisDimension() {
        IslandBlock block = bare.withLevel("smelt", 1).withLevel("gone", 3).withLevel("quartz", 1);

        GeneratorRule.Outcome outcome = rule.next(Contents.content(), IslandDimension.OVERWORLD, block, 0.0, 0.0);
        assertThat(outcome.material()).isEqualTo(Material.DIRT);
        assertThat(outcome.mob()).isNull();
    }

    @Test
    @DisplayName("past the last level an upgrade counts at its last level, so a shrunk config never removes it")
    void levelsPastTheTopCountAsTheTop() {
        IslandBlock block = bare.withLevel("coal", 9);

        assertThat(rule.chanceOf(Contents.COAL, 9)).isEqualTo(10.0);
        assertThat(rule.next(Contents.content(), IslandDimension.OVERWORLD, block, 9.0, 0.0).material())
                .isEqualTo(Material.COAL_ORE);
    }

    @Test
    @DisplayName("a dimension with an empty pool falls back to its own starting block")
    void emptyPool() {
        assertThat(rule.next(Contents.content(), IslandDimension.END, IslandBlock.fresh(BlockSpot.START), 50, 0.5)
                .material()).isEqualTo(Material.END_STONE);
    }

    @Test
    @DisplayName("the roll's total is capped at a hundred percent")
    void cappedTotal() {
        IslandBlock block = IslandBlock.fresh(BlockSpot.START).withLevel("quartz", 2);

        assertThat(rule.rollingTotal(Contents.content(), IslandDimension.NETHER, block)).isEqualTo(90.0);
        assertThat(rule.next(Contents.content(), IslandDimension.NETHER, block, 95, 0).material())
                .isEqualTo(Material.NETHERRACK);
    }

    @Test
    @DisplayName("a special's chance is its level's, capped at a hundred")
    void specialChance() {
        IslandBlock block = bare.withLevel("smelt", 1);

        assertThat(rule.specialChance(Contents.content(), IslandDimension.OVERWORLD, block,
                de.raindancer.modules.oneblock.model.Upgrade.Kind.SMELT)).isEqualTo(50.0);
        assertThat(rule.specialChance(Contents.content(), IslandDimension.END, block,
                de.raindancer.modules.oneblock.model.Upgrade.Kind.SMELT)).isZero();
    }
}
