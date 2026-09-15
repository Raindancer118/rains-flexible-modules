package de.raindancer.modules.manhunt.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules of a hunt in progress, with no server anywhere near them. */
class HuntTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());
    private static final UUID CARO = UUID.nameUUIDFromBytes("caro".getBytes());
    private static final UUID DAN = UUID.nameUUIDFromBytes("dan".getBytes());

    @Test
    @DisplayName("everybody racing who is not a Runner is a Hunter")
    void therestChase() {
        Hunt hunt = Hunt.of(Set.of(ANNA, BEN, CARO), Set.of(ANNA));

        assertThat(hunt.runners()).containsExactly(ANNA);
        assertThat(hunt.hunters()).containsExactlyInAnyOrder(BEN, CARO);
        assertThat(hunt.isHunter(BEN)).isTrue();
        assertThat(hunt.isRunner(BEN)).isFalse();
    }

    @Test
    @DisplayName("somebody on the Runner side who is not racing is in no hunt at all")
    void runnerWhoIsNotRacing() {
        Hunt hunt = Hunt.of(Set.of(ANNA, BEN), Set.of(ANNA, DAN));

        assertThat(hunt.runners()).containsExactly(ANNA);
        assertThat(hunt.everybody()).containsExactlyInAnyOrder(ANNA, BEN);
        assertThat(hunt.isHunter(DAN)).isFalse();
    }

    @Test
    @DisplayName("a death takes a Runner out of the hunt, once")
    void eliminationIsOnce() {
        Hunt hunt = Hunt.of(Set.of(ANNA, BEN, CARO), Set.of(ANNA, BEN));

        assertThat(hunt.eliminate(ANNA)).as("the first time").isTrue();
        assertThat(hunt.eliminate(ANNA)).as("a second death event for the same Runner").isFalse();
        assertThat(hunt.isEliminated(ANNA)).isTrue();
        assertThat(hunt.livingRunners()).containsExactly(BEN);
    }

    @Test
    @DisplayName("a Hunter dying eliminates nobody — they respawn and carry on")
    void huntersAreNotEliminated() {
        Hunt hunt = Hunt.of(Set.of(ANNA, CARO), Set.of(ANNA));

        assertThat(hunt.eliminate(CARO)).isFalse();
        assertThat(hunt.isEliminated(CARO)).isFalse();
        assertThat(hunt.allRunnersOut()).isFalse();
    }

    @Test
    @DisplayName("the Hunters have won once the last Runner is out, and not before")
    void allRunnersOut() {
        Hunt hunt = Hunt.of(Set.of(ANNA, BEN, CARO), Set.of(ANNA, BEN));

        hunt.eliminate(ANNA);
        assertThat(hunt.allRunnersOut()).isFalse();

        hunt.eliminate(BEN);
        assertThat(hunt.allRunnersOut()).isTrue();
        assertThat(hunt.livingRunners()).isEmpty();
    }

    /**
     * Mid-hunt side changes — the gated door, not the lobby's teams leaking in. See
     * {@link Hunt#moveToHunters} for why a snapshot is still a snapshot with this on it.
     */
    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("changing sides mid-hunt")
    class SideChanges {

        @Test
        @DisplayName("a Runner who gives up is a Hunter, and stops counting for the Runners' win")
        void runnerBecomesHunter() {
            Hunt hunt = Hunt.of(Set.of(ANNA, BEN, CARO), Set.of(ANNA, BEN));

            assertThat(hunt.moveToHunters(ANNA)).isEqualTo(Hunt.SideChange.MOVED);

            assertThat(hunt.runners()).containsExactly(BEN);
            assertThat(hunt.hunters()).containsExactlyInAnyOrder(ANNA, CARO);
            assertThat(hunt.isRunner(ANNA)).isFalse();
        }

        @Test
        @DisplayName("a Hunter who takes up running is a Runner")
        void hunterBecomesRunner() {
            Hunt hunt = Hunt.of(Set.of(ANNA, BEN), Set.of(ANNA));

            assertThat(hunt.moveToRunners(BEN)).isEqualTo(Hunt.SideChange.MOVED);

            assertThat(hunt.runners()).containsExactlyInAnyOrder(ANNA, BEN);
            assertThat(hunt.hunters()).isEmpty();
        }

        @Test
        @DisplayName("the last Runner cannot walk off the side and end the hunt by accident")
        void theLastRunnerIsRefused() {
            Hunt hunt = Hunt.of(Set.of(ANNA, BEN), Set.of(ANNA));

            assertThat(hunt.moveToHunters(ANNA)).isEqualTo(Hunt.SideChange.LAST_RUNNER);

            assertThat(hunt.runners()).containsExactly(ANNA);
        }

        @Test
        @DisplayName("a caught Runner switching sides stops being counted as caught")
        void anEliminatedRunnerSwitchingIsNoLongerOut() {
            Hunt hunt = Hunt.of(Set.of(ANNA, BEN, CARO), Set.of(ANNA, BEN));
            hunt.eliminate(ANNA);

            assertThat(hunt.moveToHunters(ANNA)).isEqualTo(Hunt.SideChange.MOVED);

            assertThat(hunt.eliminated()).isEmpty();
            assertThat(hunt.allRunnersOut()).isFalse();
        }

        @Test
        @DisplayName("somebody who is not in the hunt at all is refused, not added")
        void anOutsiderIsRefused() {
            Hunt hunt = Hunt.of(Set.of(ANNA), Set.of(ANNA));

            assertThat(hunt.moveToHunters(DAN)).isEqualTo(Hunt.SideChange.NOT_IN_THE_HUNT);
            assertThat(hunt.moveToRunners(DAN)).isEqualTo(Hunt.SideChange.NOT_IN_THE_HUNT);
            assertThat(hunt.everybody()).containsExactly(ANNA);
        }

        @Test
        @DisplayName("asking for the side somebody is already on says so")
        void alreadyThere() {
            Hunt hunt = Hunt.of(Set.of(ANNA, BEN), Set.of(ANNA));

            assertThat(hunt.moveToRunners(ANNA)).isEqualTo(Hunt.SideChange.ALREADY_THERE);
            assertThat(hunt.moveToHunters(BEN)).isEqualTo(Hunt.SideChange.ALREADY_THERE);
        }
    }
}
