package de.raindancer.modules.arena.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HoldBookTest {

    @Test
    @DisplayName("somebody held while survival updates is held until they are sent back, once")
    void heldOnce() {
        HoldBook held = new HoldBook();
        UUID ada = UUID.randomUUID();
        UUID bo = UUID.randomUUID();

        held.hold(ada);
        held.hold(bo);

        assertThat(held.isHeld(ada)).isTrue();
        assertThat(held.takeAll()).containsExactlyInAnyOrder(ada, bo);
        assertThat(held.takeAll()).isEmpty();
        assertThat(held.isHeld(ada)).isFalse();
    }

    @Test
    @DisplayName("somebody who leaves on their own is no longer waited for")
    void released() {
        HoldBook held = new HoldBook();
        UUID ada = UUID.randomUUID();
        held.hold(ada);

        held.release(ada);

        assertThat(held.takeAll()).isEmpty();
    }

    @Test
    @DisplayName("somebody still fighting stays held for later; the others are handed out")
    void fightersWait() {
        HoldBook held = new HoldBook();
        UUID ada = UUID.randomUUID();
        UUID bo = UUID.randomUUID();
        held.hold(ada);
        held.hold(bo);

        assertThat(held.takeAllBut(ada::equals)).containsExactly(bo);
        assertThat(held.isHeld(ada)).isTrue();
    }
}
