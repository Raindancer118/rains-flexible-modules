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

    @Test
    @DisplayName("Bedrock players are spared only what depends on how Bedrock moves, aims and sends packets")
    void bedrock() {
        assertThat(CheckType.REACH.fitsBedrock()).isTrue();
        assertThat(CheckType.MULTI_AURA.fitsBedrock()).isTrue();
        assertThat(CheckType.NUKER.fitsBedrock()).isTrue();
        assertThat(CheckType.GHOST_HAND.fitsBedrock()).isTrue();
        assertThat(CheckType.FLY.fitsBedrock()).isFalse();
        assertThat(CheckType.SPEED.fitsBedrock()).isFalse();
        assertThat(CheckType.AIM.fitsBedrock()).isFalse();
        assertThat(CheckType.AUTOCLICKER.fitsBedrock()).isFalse();
        assertThat(java.util.Arrays.stream(CheckType.values()).filter(CheckType::fitsBedrock)
                .allMatch(check -> check.category() == Category.COMBAT || check.category() == Category.WORLD)).isTrue();
    }
}
