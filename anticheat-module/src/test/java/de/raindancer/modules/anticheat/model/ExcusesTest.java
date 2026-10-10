package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExcusesTest {

    @Test
    @DisplayName("a miner gets every excuse: a few moves per broken block, then ordinary moves")
    void miner() {
        Excuses excuses = new Excuses();
        for (int block = 0; block < 50; block++) {
            for (int move = 0; move < 3; move++) {
                assertThat(excuses.grant(true)).as("block %d, move %d", block, move).isTrue();
            }
            for (int move = 0; move < 10; move++) {
                excuses.grant(false);
            }
        }
    }

    @Test
    @DisplayName("somebody changing blocks around themselves every tick runs out of excuses")
    void spammer() {
        Excuses excuses = new Excuses();
        int granted = 0;
        for (int move = 0; move < 100; move++) {
            if (excuses.grant(true)) {
                granted++;
            }
        }
        assertThat(granted).isLessThanOrEqualTo(Excuses.IN_A_ROW);
    }
}
