package de.raindancer.modules.performance.rules;

import de.raindancer.modules.performance.model.ChunkCensus;
import de.raindancer.modules.performance.model.EntityGroup;
import de.raindancer.modules.performance.model.Finding;
import de.raindancer.modules.performance.model.Fix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What counts as a problem in one chunk, and what could be done about it. */
class FindingRuleTest {

    private final FindingRule rule = new FindingRule(FindingRule.Limits.DEFAULTS);

    private static ChunkCensus chunk(int x, int z) {
        return new ChunkCensus("minecraft:overworld", x, z);
    }

    @Test
    @DisplayName("Lilly's chicken farm: 218 chickens in one chunk is a crowd, to be thinned to the farm limit")
    void chickenFarm() {
        ChunkCensus farm = chunk(72, 79);
        farm.entities("chicken", EntityGroup.ANIMAL, 218);
        farm.entities("item", EntityGroup.ITEM, 52);

        List<Finding> found = rule.findings(List.of(farm), 50);

        assertThat(found).hasSize(1);
        Finding crowd = found.getFirst();
        assertThat(crowd.kind()).isEqualTo(Finding.Kind.ANIMAL_CROWD);
        assertThat(crowd.what()).isEqualTo("chicken");
        assertThat(crowd.count()).isEqualTo(218);
        assertThat(crowd.fixes()).containsExactly(Fix.thin("chicken", 50), Fix.tellOwner());
    }

    @Test
    @DisplayName("a pile of items is offered a clear — items vanish by themselves anyway, just later")
    void itemPile() {
        ChunkCensus pile = chunk(50, -11);
        pile.entities("item", EntityGroup.ITEM, 160);
        pile.entities("experience_orb", EntityGroup.ITEM, 40);

        Finding found = rule.findings(List.of(pile), 50).getFirst();

        assertThat(found.kind()).isEqualTo(Finding.Kind.ITEM_PILE);
        assertThat(found.count()).isEqualTo(200);
        assertThat(found.fixes()).containsExactly(Fix.clearItems(), Fix.tellOwner());
    }

    @Test
    @DisplayName("a crowd of monsters is offered a clear of those that would despawn anyway")
    void mobFarm() {
        ChunkCensus farm = chunk(1, 1);
        farm.entities("zombie", EntityGroup.MONSTER, 70);

        assertThat(rule.findings(List.of(farm), 50).getFirst().fixes())
                .containsExactly(Fix.clearMonsters(), Fix.tellOwner());
    }

    @Test
    @DisplayName("villagers and hoppers are only reported — nothing of a player's is removed for those")
    void reportOnly() {
        ChunkCensus hall = chunk(2, 2);
        hall.entities("villager", EntityGroup.VILLAGER, 45);
        ChunkCensus sorter = chunk(3, 3);
        sorter.blockEntities("hopper", 170);

        List<Finding> found = rule.findings(List.of(hall, sorter), 50);

        assertThat(found).extracting(Finding::kind)
                .containsExactlyInAnyOrder(Finding.Kind.VILLAGER_CROWD, Finding.Kind.BLOCK_ENTITY_CLUSTER);
        assertThat(found).allSatisfy(finding -> assertThat(finding.fixes()).containsExactly(Fix.tellOwner()));
    }

    @Test
    @DisplayName("an ordinary chunk is nothing to report")
    void quiet() {
        ChunkCensus field = chunk(0, 0);
        field.entities("cow", EntityGroup.ANIMAL, 12);
        field.entities("item", EntityGroup.ITEM, 9);
        field.blockEntities("chest", 30);

        assertThat(rule.findings(List.of(field), 50)).isEmpty();
    }

    @Test
    @DisplayName("worst first, so a report leads with what matters")
    void ordered() {
        ChunkCensus small = chunk(0, 0);
        small.entities("cow", EntityGroup.ANIMAL, 90);
        ChunkCensus big = chunk(1, 0);
        big.entities("chicken", EntityGroup.ANIMAL, 300);

        assertThat(rule.findings(List.of(small, big), 50)).extracting(Finding::count).containsExactly(300, 90);
    }

    @Test
    @DisplayName("thinning never goes below the farm limit, and with no farm limit keeps the crowd threshold")
    void thinTarget() {
        ChunkCensus farm = chunk(0, 0);
        farm.entities("sheep", EntityGroup.ANIMAL, 120);

        assertThat(rule.findings(List.of(farm), 0).getFirst().fixes().getFirst())
                .isEqualTo(Fix.thin("sheep", FindingRule.Limits.DEFAULTS.animalsOfOneKind()));
    }
}
