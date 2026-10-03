package de.raindancer.modules.speedrun.manhunt.tracker;

import de.raindancer.modules.speedrun.manhunt.tracker.CompassHandout.Kind;
import de.raindancer.modules.speedrun.manhunt.tracker.CompassHandout.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** /manhunt give — when a compass is handed over, and why it is not. */
@DisplayName("handing somebody a missing compass")
class CompassHandoutTest {

    @Test
    @DisplayName("given only into a free slot, and only to somebody who is owed one")
    void given() {
        assertThat(CompassHandout.decide(true, true, true, false, true)).isEqualTo(Outcome.GIVEN);
    }

    @Test
    @DisplayName("a full inventory is refused rather than something being pushed out or dropped")
    void fullInventory() {
        assertThat(CompassHandout.decide(true, true, true, false, false)).isEqualTo(Outcome.NO_ROOM);
    }

    @Test
    @DisplayName("somebody already carrying that compass keeps it — no second one, no replacement")
    void alreadyCarries() {
        assertThat(CompassHandout.decide(true, true, true, true, true)).isEqualTo(Outcome.ALREADY_HAS);
        assertThat(CompassHandout.decide(true, true, true, true, false)).isEqualTo(Outcome.ALREADY_HAS);
    }

    @Test
    @DisplayName("outside a hunt there is nothing for a compass to point at")
    void noHunt() {
        assertThat(CompassHandout.decide(false, true, true, false, true)).isEqualTo(Outcome.NO_HUNT);
    }

    @Test
    @DisplayName("a compass the owner switched off stays off")
    void switchedOff() {
        assertThat(CompassHandout.decide(true, false, true, false, true)).isEqualTo(Outcome.SWITCHED_OFF);
    }

    @Test
    @DisplayName("a compass for the other side, or for somebody out, would be a dead item")
    void notTheirs() {
        assertThat(CompassHandout.decide(true, true, false, false, true)).isEqualTo(Outcome.NOT_THEIRS);
    }

    @Test
    @DisplayName("the kinds are typed the way they are named")
    void kinds() {
        assertThat(Kind.parse("tracker")).contains(Kind.TRACKER);
        assertThat(Kind.parse("TEAM")).contains(Kind.TEAM);
        assertThat(Kind.parse("structure")).contains(Kind.STRUCTURE);
        assertThat(Kind.parse("sword")).isEqualTo(Optional.empty());
    }
}
