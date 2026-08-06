package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.Participant;
import de.raindancer.modules.hungergames.model.ParticipantState;
import de.raindancer.modules.hungergames.screen.TributesMenu;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TributesMenu#sortedParticipants} pulled the page's ordering into a pure method precisely so it
 * could be checked without a server — a gamemaster reading the tribute list expects the living at the top,
 * not wherever the registry happens to iterate them.
 */
class TributesMenuOrderingTest {

    private static Participant of(String name, boolean alive) {
        return new Participant(UUID.randomUUID(), name,
                alive ? ParticipantState.ALIVE : ParticipantState.ELIMINATED, Optional.empty());
    }

    @Test
    @DisplayName("living tributes come before eliminated ones")
    void aliveFirst() {
        Participant dead = of("Zed", false);
        Participant alive = of("Amy", true);

        List<Participant> sorted = TributesMenu.sortedParticipants(List.of(dead, alive));

        assertThat(sorted).containsExactly(alive, dead);
    }

    @Test
    @DisplayName("within the same state, tributes are alphabetical and case-insensitive")
    void alphabeticalWithinState() {
        Participant bob = of("bob", true);
        Participant amy = of("Amy", true);
        Participant zed = of("Zed", true);

        List<Participant> sorted = TributesMenu.sortedParticipants(List.of(bob, zed, amy));

        assertThat(sorted).containsExactly(amy, bob, zed);
    }

    @Test
    @DisplayName("an empty roster sorts to an empty list, not an error")
    void emptyRoster() {
        assertThat(TributesMenu.sortedParticipants(List.of())).isEmpty();
    }
}
