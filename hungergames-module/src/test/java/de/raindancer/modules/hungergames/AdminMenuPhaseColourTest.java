package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.screen.AdminMenu;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdminMenu#phaseColour} answers, in one place, what colour the status head shows for each phase —
 * pulled out of {@code render()} so the mapping can be checked without opening a menu, and so a phase added
 * later that nobody updates the switch for fails to compile rather than showing up unpainted.
 */
class AdminMenuPhaseColourTest {

    @Test
    @DisplayName("every phase has its own colour, and none of them collide by accident")
    void everyPhaseIsCovered() {
        for (GamePhase phase : GamePhase.values()) {
            assertThat(AdminMenu.phaseColour(phase)).isNotBlank();
        }
    }

    @Test
    @DisplayName("running is green, finished is gold — the two states a gamemaster checks most")
    void theTwoStatesThatMatterMost() {
        assertThat(AdminMenu.phaseColour(GamePhase.RUNNING)).isEqualTo("<green>");
        assertThat(AdminMenu.phaseColour(GamePhase.FINISHED)).isEqualTo("<gold>");
        assertThat(AdminMenu.phaseColour(GamePhase.NOT_INITIALIZED)).isEqualTo("<dark_gray>");
    }
}
