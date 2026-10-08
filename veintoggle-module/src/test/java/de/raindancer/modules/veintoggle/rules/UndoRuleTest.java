package de.raindancer.modules.veintoggle.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Which blocks of a vein go back: only those with room, and only as far as their drops can be returned. */
class UndoRuleTest {

    private final UndoRule rule = new UndoRule();

    private record Block(String name, Map<String, Integer> drops) {
    }

    private UndoRule.Plan<Block, String> plan(List<Block> blocks, Set<String> occupied, Map<String, Integer> pool) {
        return rule.plan(blocks, block -> !occupied.contains(block.name()), Block::drops, new HashMap<>(pool));
    }

    @Test
    @DisplayName("every block goes back when everything it dropped can be taken back")
    void all() {
        Block a = new Block("a", Map.of("diamond", 1));
        Block b = new Block("b", Map.of("diamond", 2, "cobble", 1));

        UndoRule.Plan<Block, String> plan = plan(List.of(a, b), Set.of(), Map.of("diamond", 5, "cobble", 1));

        assertThat(plan.restore()).containsExactly(a, b);
        assertThat(plan.inTheWay()).isEmpty();
        assertThat(plan.unpaid()).isEmpty();
        assertThat(plan.toTake()).containsExactlyInAnyOrderEntriesOf(Map.of("diamond", 3, "cobble", 1));
    }

    @Test
    @DisplayName("a block whose drops are gone stays mined — the rest still goes back")
    void unpaid() {
        Block a = new Block("a", Map.of("diamond", 2));
        Block b = new Block("b", Map.of("diamond", 2));
        Block c = new Block("c", Map.of("diamond", 1));

        UndoRule.Plan<Block, String> plan = plan(List.of(a, b, c), Set.of(), Map.of("diamond", 3));

        assertThat(plan.restore()).containsExactly(a, c);
        assertThat(plan.unpaid()).containsExactly(b);
        assertThat(plan.toTake()).containsExactlyEntriesOf(Map.of("diamond", 3));
    }

    @Test
    @DisplayName("a block with something in its place is not overwritten, and nothing is taken for it")
    void inTheWay() {
        Block a = new Block("a", Map.of("diamond", 1));
        Block b = new Block("b", Map.of("diamond", 1));

        UndoRule.Plan<Block, String> plan = plan(List.of(a, b), Set.of("a"), Map.of("diamond", 1));

        assertThat(plan.restore()).containsExactly(b);
        assertThat(plan.inTheWay()).containsExactly(a);
        assertThat(plan.toTake()).containsExactlyEntriesOf(Map.of("diamond", 1));
    }

    @Test
    @DisplayName("a block that dropped nothing goes back for nothing")
    void droppedNothing() {
        Block a = new Block("a", Map.of());

        UndoRule.Plan<Block, String> plan = plan(List.of(a), Set.of(), Map.of());

        assertThat(plan.restore()).containsExactly(a);
        assertThat(plan.toTake()).isEmpty();
    }

    @Test
    @DisplayName("the pool it was given is not changed by asking")
    void noSideEffects() {
        Map<String, Integer> pool = new HashMap<>(Map.of("diamond", 1));
        rule.plan(List.of(new Block("a", Map.of("diamond", 1))), block -> true, Block::drops, pool);
        assertThat(pool).containsExactlyEntriesOf(Map.of("diamond", 1));
    }

    @Test
    @DisplayName("a vein is undoable only once it has stopped falling, and only for a while after")
    void timing() {
        assertThat(rule.settled(1_000, 1_000 + UndoRule.SETTLED_AFTER_MILLIS - 1)).isFalse();
        assertThat(rule.settled(1_000, 1_000 + UndoRule.SETTLED_AFTER_MILLIS)).isTrue();
        assertThat(rule.expired(1_000, 61_000, 60_000)).isFalse();
        assertThat(rule.expired(1_000, 61_001, 60_000)).isTrue();
        assertThat(rule.expired(1_000, 1_000, 0)).as("zero switches undo off").isTrue();
    }

    @Test
    @DisplayName("money buys whole items only, kinds in order, and nothing without a price")
    void affordable() {
        java.util.Map<String, Integer> wanted = new java.util.LinkedHashMap<>();
        wanted.put("diamond", 3);
        wanted.put("coal", 5);
        wanted.put("bedrock", 1);
        java.util.function.Function<String, java.util.Optional<de.raindancer.core.social.economy.Money>> price =
                kind -> switch (kind) {
                    case "diamond" -> java.util.Optional.of(de.raindancer.core.social.economy.Money.of(1_000));
                    case "coal" -> java.util.Optional.of(de.raindancer.core.social.economy.Money.of(10));
                    default -> java.util.Optional.empty();
                };

        assertThat(rule.affordable(wanted, price, de.raindancer.core.social.economy.Money.of(2_035)))
                .containsExactly(java.util.Map.entry("diamond", 2), java.util.Map.entry("coal", 3));
        assertThat(rule.cost(wanted, price)).isEqualTo(de.raindancer.core.social.economy.Money.of(3_050));
        assertThat(rule.affordable(wanted, price, de.raindancer.core.social.economy.Money.of(-5))).isEmpty();
    }
}
