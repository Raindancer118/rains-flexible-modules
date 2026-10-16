package de.raindancer.modules.ctf.rules;

import de.raindancer.modules.ctf.model.TieBreak;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("who wins")
class WinRuleTest {

    private final WinRule rule = new WinRule();

    @Test
    @DisplayName("reaching the target wins at once")
    void target() {
        assertThat(rule.afterCapture(new int[]{3, 1}, 3, false)).contains(0);
        assertThat(rule.afterCapture(new int[]{2, 1}, 3, false)).isEmpty();
    }

    @Test
    @DisplayName("in overtime the next capture wins")
    void overtimeCapture() {
        assertThat(rule.afterCapture(new int[]{2, 3}, 5, true)).contains(1);
    }

    @Test
    @DisplayName("time up: the side ahead wins")
    void timeUpAhead() {
        assertThat(rule.atTimeUp(new int[]{1, 2}, TieBreak.SUDDEN_DEATH, false))
                .isEqualTo(new WinRule.Ending(WinRule.Ending.Kind.WIN, 1));
    }

    @Test
    @DisplayName("time up level: overtime, as the setting says, or a draw")
    void timeUpLevel() {
        assertThat(rule.atTimeUp(new int[]{2, 2}, TieBreak.SUDDEN_DEATH, false).kind())
                .isEqualTo(WinRule.Ending.Kind.SUDDEN_DEATH);
        assertThat(rule.atTimeUp(new int[]{2, 2}, TieBreak.NEXT_CAPTURE, false).kind())
                .isEqualTo(WinRule.Ending.Kind.OVERTIME);
        assertThat(rule.atTimeUp(new int[]{2, 2}, TieBreak.DRAW, false).kind()).isEqualTo(WinRule.Ending.Kind.DRAW);
    }

    @Test
    @DisplayName("overtime running out level is a draw — it does not go on for ever")
    void overtimeOut() {
        assertThat(rule.atTimeUp(new int[]{2, 2}, TieBreak.SUDDEN_DEATH, true).kind())
                .isEqualTo(WinRule.Ending.Kind.DRAW);
    }

    @Test
    @DisplayName("with three sides, only the top ones count for a tie")
    void threeSides() {
        assertThat(rule.atTimeUp(new int[]{2, 2, 0}, TieBreak.DRAW, false).kind()).isEqualTo(WinRule.Ending.Kind.DRAW);
        assertThat(rule.atTimeUp(new int[]{0, 0, 1}, TieBreak.DRAW, false)).isEqualTo(
                new WinRule.Ending(WinRule.Ending.Kind.WIN, 2));
    }

    @Test
    @DisplayName("a side left alone wins by forfeit")
    void forfeit() {
        assertThat(rule.lastSideStanding(new int[]{3, 0})).contains(0);
        assertThat(rule.lastSideStanding(new int[]{3, 1})).isEmpty();
        assertThat(rule.lastSideStanding(new int[]{0, 0})).isEmpty();
    }
}
