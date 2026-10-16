package de.raindancer.modules.oneblock.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PendingCommandsTest {

    private final AtomicLong clock = new AtomicLong(0);
    private final PendingCommands pending = new PendingCommands(clock::get);
    private final UUID player = UUID.randomUUID();

    @Test
    @DisplayName("what was typed elsewhere runs once on arrival, the latest winning")
    void once() {
        pending.put(player, "nether");
        pending.put(player, " end ");

        assertThat(pending.take(player)).contains("end");
        assertThat(pending.take(player)).isEmpty();
    }

    @Test
    @DisplayName("a command nobody arrived for in time is dropped")
    void tooOld() {
        pending.put(player, "nether");
        clock.set(PendingCommands.KEEP_MILLIS + 1);

        assertThat(pending.take(player)).isEmpty();
    }

    @Test
    @DisplayName("nothing typed is nothing kept")
    void blank() {
        pending.put(player, "nether");
        pending.put(player, "  ");

        assertThat(pending.take(player)).isEmpty();
    }
}
