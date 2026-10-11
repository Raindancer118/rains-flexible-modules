package de.raindancer.modules.sends.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItemSummaryTest {

    @Test
    @DisplayName("stacks of the same thing are added together, in the order first seen")
    void merges() {
        List<ItemSummary.Line> lines = ItemSummary.lines(List.of(
                new ItemSummary.Line("Diamond", 3), new ItemSummary.Line("Stone", 64),
                new ItemSummary.Line("Diamond", 5)));
        assertThat(lines).containsExactly(new ItemSummary.Line("Diamond", 8), new ItemSummary.Line("Stone", 64));
    }

    @Test
    @DisplayName("a line names the first few kinds and counts the rest")
    void describes() {
        List<ItemSummary.Line> stacks = List.of(new ItemSummary.Line("A", 1), new ItemSummary.Line("B", 2),
                new ItemSummary.Line("C", 3), new ItemSummary.Line("D", 4), new ItemSummary.Line("E", 5));
        assertThat(ItemSummary.describe(stacks, 3)).isEqualTo("1× A, 2× B, 3× C, 2 more kinds");
        assertThat(ItemSummary.describe(stacks.subList(0, 4), 3)).isEqualTo("1× A, 2× B, 3× C, 1 more kind");
        assertThat(ItemSummary.describe(stacks.subList(0, 2), 3)).isEqualTo("1× A, 2× B");
    }

    @Test
    @DisplayName("a material name reads as words")
    void humanises() {
        assertThat(ItemSummary.humanise("DIAMOND_PICKAXE")).isEqualTo("Diamond Pickaxe");
        assertThat(ItemSummary.humanise("STONE")).isEqualTo("Stone");
    }
}
