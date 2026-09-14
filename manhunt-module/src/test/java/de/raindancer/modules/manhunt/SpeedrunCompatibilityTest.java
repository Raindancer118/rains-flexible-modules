package de.raindancer.modules.manhunt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That Manhunt refuses to start against a RainsSpeedrun too old for it — in words, not with a
 * linkage error.
 *
 * <p>Reported live: RainsManhunt 0.9.1 beside RainsSpeedrun 1.9.0 died on enable with
 * "IllegalAccessError: failed to access class de.raindancer.modules.speedrun.SpeedrunTimerDisplay".
 * That class became public in 1.10.0, and a Paper descriptor can require a plugin but not a version
 * of it, so nothing stopped the old jar being loaded. The error was accurate and useless: it names a
 * class, not the jar that has to be replaced.
 */
class SpeedrunCompatibilityTest {

    /** Stands in for the 1.9.0 shape of the class: present, but not public. */
    static final class NotPublicYet {
    }

    @Test
    @DisplayName("the RainsSpeedrun this module is built against passes the check")
    void currentSpeedrunIsAccepted() throws Exception {
        Class<?> display = Class.forName("de.raindancer.modules.speedrun.SpeedrunTimerDisplay");

        assertThatCode(() -> ManhuntModule.requireCurrentSpeedrun(display, "1.10.0"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an older RainsSpeedrun is refused with the jar to replace, not a class name")
    void olderSpeedrunIsRefusedInWords() {
        assertThatThrownBy(() -> ManhuntModule.requireCurrentSpeedrun(NotPublicYet.class, "1.9.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RainsSpeedrun 1.10.0")
                .hasMessageContaining("1.9.0");
    }

    @Test
    @DisplayName("a RainsSpeedrun whose version cannot be read is still named in the refusal")
    void unknownVersionStillSaysWhatIsNeeded() {
        assertThatThrownBy(() -> ManhuntModule.requireCurrentSpeedrun(NotPublicYet.class, null))
                .hasMessageContaining("RainsSpeedrun 1.10.0");
    }
}
