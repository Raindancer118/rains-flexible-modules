package de.raindancer.modules.tpa;

import de.raindancer.modules.tpa.model.TpaPrefs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one player has decided about being asked.
 *
 * <h2>Two switches, not one</h2>
 * A blanket "nobody may ask me" and a list of particular people are different needs: somebody building
 * something wants an hour's quiet from everybody, and somebody being pestered wants one person gone for
 * good. Folding them together would mean the second could only be had by taking the first.
 *
 * <h2>Why a blocked person is told the same thing as everybody else</h2>
 * Because the alternative tells them they have been blocked, which turns a quiet decision into a
 * confrontation. "Not accepting requests right now" is what everybody sees, and it is true.
 */
class TpaPrefsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @Nested
    @DisplayName("to begin with")
    class Defaults {

        @Test
        @DisplayName("everybody accepts requests and has blocked nobody")
        void theDefault() {
            TpaPrefs fresh = TpaPrefs.untouched();

            assertThat(fresh.accepting()).isTrue();
            assertThat(fresh.blocked()).isEmpty();
            assertThat(fresh.mayBeAskedBy(ALICE)).isTrue();
        }

        @Test
        @DisplayName("somebody who has changed nothing is not worth writing down")
        void theDefaultIsNotWorthStoring() {
            // The old file only held players who had actually decided something, and that is worth
            // keeping: a server writes one entry per person who has ever used the plugin otherwise,
            // and every one of them says nothing.
            assertThat(TpaPrefs.untouched().isWorthKeeping()).isFalse();
            assertThat(TpaPrefs.untouched().refusingEverybody().isWorthKeeping()).isTrue();
            assertThat(TpaPrefs.untouched().blocking(ALICE).isWorthKeeping()).isTrue();
        }
    }

    @Nested
    @DisplayName("the blanket switch")
    class Toggling {

        @Test
        @DisplayName("refusing everybody refuses everybody")
        void refusingEverybody() {
            TpaPrefs quiet = TpaPrefs.untouched().refusingEverybody();

            assertThat(quiet.accepting()).isFalse();
            assertThat(quiet.mayBeAskedBy(ALICE)).isFalse();
            assertThat(quiet.mayBeAskedBy(BOB)).isFalse();
        }

        @Test
        @DisplayName("it can be turned back on")
        void turningItBackOn() {
            assertThat(TpaPrefs.untouched().refusingEverybody().acceptingEverybody().accepting())
                    .isTrue();
        }

        @Test
        @DisplayName("turning it back on does not forget who was blocked")
        void theBlockListSurvivesTheToggle() {
            // Two decisions, kept apart. Somebody who went quiet for an hour and comes back has not
            // forgiven the person they blocked last week.
            TpaPrefs prefs = TpaPrefs.untouched()
                    .blocking(ALICE)
                    .refusingEverybody()
                    .acceptingEverybody();

            assertThat(prefs.mayBeAskedBy(ALICE)).isFalse();
            assertThat(prefs.mayBeAskedBy(BOB)).isTrue();
        }
    }

    @Nested
    @DisplayName("blocking one person")
    class Blocking {

        @Test
        @DisplayName("a blocked person may not ask, and nobody else is affected")
        void blockingOne() {
            TpaPrefs prefs = TpaPrefs.untouched().blocking(ALICE);

            assertThat(prefs.mayBeAskedBy(ALICE)).isFalse();
            assertThat(prefs.mayBeAskedBy(BOB)).isTrue();
        }

        @Test
        @DisplayName("blocking somebody twice is blocking them once")
        void blockingTwice() {
            assertThat(TpaPrefs.untouched().blocking(ALICE).blocking(ALICE).blocked()).hasSize(1);
        }

        @Test
        @DisplayName("they can be unblocked again")
        void unblocking() {
            TpaPrefs prefs = TpaPrefs.untouched().blocking(ALICE).unblocking(ALICE);

            assertThat(prefs.mayBeAskedBy(ALICE)).isTrue();
            assertThat(prefs.blocked()).isEmpty();
        }

        @Test
        @DisplayName("unblocking somebody who was never blocked changes nothing")
        void unblockingNobody() {
            assertThat(TpaPrefs.untouched().unblocking(ALICE).blocked()).isEmpty();
        }

        @Test
        @DisplayName("whether somebody is on the list can be asked")
        void asking() {
            assertThat(TpaPrefs.untouched().blocking(ALICE).hasBlocked(ALICE)).isTrue();
            assertThat(TpaPrefs.untouched().hasBlocked(ALICE)).isFalse();
        }

        @Test
        @DisplayName("nobody is not somebody to block")
        void nullIsIgnored() {
            assertThat(TpaPrefs.untouched().blocking(null).blocked()).isEmpty();
            assertThat(TpaPrefs.untouched().unblocking(null).blocked()).isEmpty();
            assertThat(TpaPrefs.untouched().mayBeAskedBy(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("the value itself")
    class Immutability {

        @Test
        @DisplayName("changing one leaves the original alone")
        void nothingIsMutated() {
            // Held in a concurrent map and read from several threads. A prefs object that could be
            // changed under a reader is a block list that is briefly empty while somebody is asking.
            TpaPrefs original = TpaPrefs.untouched();

            original.blocking(ALICE).refusingEverybody();

            assertThat(original.accepting()).isTrue();
            assertThat(original.blocked()).isEmpty();
        }

        @Test
        @DisplayName("the list handed out cannot be changed from outside")
        void theListIsACopy() {
            Set<UUID> blocked = TpaPrefs.untouched().blocking(ALICE).blocked();

            assertThat(blocked).isUnmodifiable();
        }
    }
}
