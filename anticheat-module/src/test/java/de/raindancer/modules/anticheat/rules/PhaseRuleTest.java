package de.raindancer.modules.anticheat.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhaseRuleTest {

    private final PhaseRule rule = new PhaseRule();

    /** A wall of unit thickness at x 5..6, from the floor at y 0 up. */
    private static final PhaseRule.Space WALL = (minX, minY, minZ, maxX, maxY, maxZ) ->
            minX < 6 && maxX > 5 && maxY > 0;

    @Test
    @DisplayName("a level move that ends on the far side of a wall went through it")
    void throughTheWall() {
        Judgement judged = rule.judge(WALL, 4.2, 1, 0.5, 6.9, 1, 0.5, 0.6, 1.8);
        assertThat(judged.failed()).isTrue();
        assertThat(judged.reason()).contains("through");
    }

    @Test
    @DisplayName("a level move in the open is fine, however far, and so is one that stops at the wall")
    void inTheOpen() {
        assertThat(rule.judge(WALL, 0.5, 1, 0.5, 3.0, 1, 0.5, 0.6, 1.8).passed()).isTrue();
        assertThat(rule.judge(WALL, 4.0, 1, 0.5, 4.69, 1, 0.5, 0.6, 1.8).passed()).isTrue();
    }

    @Test
    @DisplayName("moves that change height are left to the other checks: a step up passes a block's corner")
    void climbing() {
        assertThat(rule.judge(WALL, 4.2, 1, 0.5, 6.9, 1.4, 0.5, 0.6, 1.8).passed()).isTrue();
    }

    @Test
    @DisplayName("a short move is judged at its end only, by the inside check")
    void shortMove() {
        assertThat(rule.judge(WALL, 4.5, 1, 0.5, 4.8, 1, 0.5, 0.6, 1.8).passed()).isTrue();
    }

    @Test
    @DisplayName("a thin wall does not slip between the sample points")
    void thin() {
        PhaseRule.Space pane = (minX, minY, minZ, maxX, maxY, maxZ) -> minX < 5.5625 && maxX > 5.4375 && maxY > 0;
        assertThat(rule.judge(pane, 4.3, 1, 0.5, 6.9, 1, 0.5, 0.6, 1.8).failed()).isTrue();
    }

    @Test
    @DisplayName("a far clip is swept too: twenty blocks through a pane in one move")
    void farClip() {
        PhaseRule.Space pane = (minX, minY, minZ, maxX, maxY, maxZ) -> minX < 11.5625 && maxX > 11.4375 && maxY > 0;
        assertThat(rule.judge(pane, 2.0, 1, 0.5, 24.0, 1, 0.5, 0.6, 1.8).failed()).isTrue();
        assertThat(rule.judge(pane, 12.0, 1, 0.5, 33.0, 1, 0.5, 0.6, 1.8).passed()).isTrue();
    }
}
