package de.raindancer.modules.manhunt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That Manhunt refuses to start against a RainsSpeedrun too old for it — in words, not with a
 * linkage error.
 *
 * <p>Reported live once already: RainsManhunt 0.9.1 beside RainsSpeedrun 1.9.0 died on enable with
 * "IllegalAccessError: failed to access class de.raindancer.modules.speedrun.SpeedrunTimerDisplay".
 * A Paper descriptor can require a plugin but not a version of it, so nothing stopped the old jar
 * being loaded. The error was accurate and useless: it names a class, not the jar to replace. The
 * class this checks for now is {@code SpeedrunModes}, which is the seam this whole module hangs on —
 * without it there is no way for a hunt to be played at all.
 */
class SpeedrunCompatibilityTest {

    /** Stands in for an older RainsSpeedrun: the class this module needs is simply not there. */
    @Test
    @DisplayName("the RainsSpeedrun this module is built against passes the check")
    void currentSpeedrunIsAccepted() throws Exception {
        Class<?> modes = Class.forName("de.raindancer.modules.speedrun.SpeedrunModes");

        assertThatCode(() -> ManhuntModule.requireCurrentSpeedrun(modes, "1.11.0"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an older RainsSpeedrun is refused with the jar to replace, not a class name")
    void olderSpeedrunIsRefusedInWords() {
        assertThatThrownBy(() -> ManhuntModule.requireCurrentSpeedrun(null, "1.10.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RainsSpeedrun 1.11.0")
                .hasMessageContaining("1.10.1");
    }

    @Test
    @DisplayName("a RainsSpeedrun whose version cannot be read is still named in the refusal")
    void unknownVersionStillSaysWhatIsNeeded() {
        assertThatThrownBy(() -> ManhuntModule.requireCurrentSpeedrun(null, null))
                .hasMessageContaining("RainsSpeedrun 1.11.0");
    }
}
