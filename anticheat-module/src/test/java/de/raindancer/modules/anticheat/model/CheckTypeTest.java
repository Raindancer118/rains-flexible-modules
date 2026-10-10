package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckTypeTest {

    @Test
    @DisplayName("movement and packet checks model this server's version; combat, world and inventory checks do not depend on it")
    void versionBound() {
        assertThat(CheckType.FLY.versionBound()).isTrue();
        assertThat(CheckType.SPEED.versionBound()).isTrue();
        assertThat(CheckType.SPRINT.versionBound()).isTrue();
        assertThat(CheckType.TIMER.versionBound()).isTrue();
        assertThat(CheckType.BAD_PACKETS.versionBound()).isTrue();
        assertThat(CheckType.POST.versionBound()).isTrue();
        assertThat(CheckType.NO_SWING.versionBound()).as("swing order changed in 26.3").isTrue();
        assertThat(CheckType.KEEP_SPRINT.versionBound()).isTrue();

        assertThat(CheckType.REACH.versionBound()).isFalse();
        assertThat(CheckType.MULTI_AURA.versionBound()).isFalse();
        assertThat(CheckType.FAST_BREAK.versionBound()).isFalse();
        assertThat(CheckType.NUKER.versionBound()).isFalse();
        assertThat(CheckType.FAST_CLICK.versionBound()).isFalse();
        assertThat(CheckType.CLIENT.versionBound()).isFalse();
    }
}
