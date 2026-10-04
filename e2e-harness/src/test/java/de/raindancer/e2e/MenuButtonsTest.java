package de.raindancer.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MenuButtonsTest {

    @Test
    @DisplayName("a button whose name carries its state is one button in every state")
    void stateIsNotIdentity() {
        assertThat(MenuButtons.key("Hub", 13, "Goal: [Free the End]", false))
                .isEqualTo(MenuButtons.key("Hub", 13, "Goal: [Mine a diamond]", false));
        assertThat(MenuButtons.key("Hub", 15, "Death policy: OFF", false))
                .isEqualTo(MenuButtons.key("Hub", 15, "Death policy: END_FOR_ALL", false));
        assertThat(MenuButtons.key("Run", 0, "0:55 Over", false)).isEqualTo(MenuButtons.key("Run", 0, "2:52 Over", false));
    }

    @Test
    @DisplayName("a switch's two sides are two buttons, and a slot is part of what a button is")
    void differentWordsDifferentButtons() {
        assertThat(MenuButtons.key("Hub", 25, "You are racing", false))
                .isNotEqualTo(MenuButtons.key("Hub", 25, "You are not racing", false));
        assertThat(MenuButtons.key("Hub", 45, "Back", false)).isNotEqualTo(MenuButtons.key("Hub", 46, "Back", false));
    }

    @Test
    @DisplayName("a list's entries are one button, whatever they show and wherever they sit")
    void entriesAreOne() {
        assertThat(MenuButtons.key("List", 0, "Village", true)).isEqualTo(MenuButtons.key("List", 7, "Igloo", true));
        assertThat(MenuButtons.key("List", 0, "Village", true)).isNotEqualTo(MenuButtons.key("List", 0, "Village", false));
    }
}
