package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputsTest {

    @Test
    @DisplayName("every key survives the round trip through the packed form")
    void roundTrip() {
        int packed = Inputs.of(true, false, true, false, true, false, true);
        assertThat(Inputs.known(packed)).isTrue();
        assertThat(Inputs.forward(packed)).isTrue();
        assertThat(Inputs.backward(packed)).isFalse();
        assertThat(Inputs.left(packed)).isTrue();
        assertThat(Inputs.right(packed)).isFalse();
        assertThat(Inputs.jump(packed)).isTrue();
        assertThat(Inputs.sneak(packed)).isFalse();
        assertThat(Inputs.sprint(packed)).isTrue();
    }

    @Test
    @DisplayName("no keys held is still a known input; nothing seen yet is not")
    void unknown() {
        assertThat(Inputs.known(Inputs.of(false, false, false, false, false, false, false))).isTrue();
        assertThat(Inputs.known(Inputs.UNKNOWN)).isFalse();
        assertThat(Inputs.walking(Inputs.of(false, false, false, false, true, true, false))).isFalse();
        assertThat(Inputs.walking(Inputs.of(false, false, false, true, false, false, false))).isTrue();
    }
}
