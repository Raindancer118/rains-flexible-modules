package de.raindancer.modules.manhunt.tracker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "has this Hunter already been sent this target" guard. It exists to stop a packet per Hunter
 * per sweep for no change at all — and it is also what makes a forgotten target worth having: the
 * server re-sends a client's spawn position on its own (a respawn, a dimension change), which
 * silently overwrites a compass target this remembers having sent, so the sweep would never send it
 * again. See {@code TrackerCompassService.resyncNeedle}.
 */
class CompassTargetsTest {

    private static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());

    private final CompassTargets targets = new CompassTargets();

    @Test
    @DisplayName("the first target is always a change; the same one again is not")
    void onlyRealChangesCount() {
        assertThat(targets.moved(HUNTER, "world", 1, 2, 3)).isTrue();
        assertThat(targets.moved(HUNTER, "world", 1, 2, 3)).isFalse();
        assertThat(targets.moved(HUNTER, "world", 1, 2, 4)).isTrue();
        assertThat(targets.moved(HUNTER, "world_nether", 1, 2, 4)).isTrue();
    }

    @Test
    @DisplayName("a forgotten Hunter is sent their target again, even if it has not moved")
    void forgettingForcesAResend() {
        targets.moved(HUNTER, "world", 1, 2, 3);

        targets.forget(HUNTER);

        assertThat(targets.moved(HUNTER, "world", 1, 2, 3))
                .as("the client's spawn position may have been overwritten since")
                .isTrue();
    }
}
