package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.service.MedikitCountdownService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The medikit heals a few seconds after it is used, and being hit in between cancels it.
 *
 * <h2>The regression this was written for</h2>
 * The source ran the medikit on {@code items.medikit.countdown-seconds} — three by default — and aborted the
 * treatment on any damage ({@code CustomItems.abortMedikitOnDamage}). The port healed on the click, and said
 * so in its own javadoc: "the countdown itself needs a scheduler and a damage listener, neither of which this
 * class may touch".
 *
 * <p>That is the difference between the most valuable item in the sponsor shop being a gamble and being free.
 * With the wind-up, using one mid-fight is a bet that the person hitting you misses for three seconds, and
 * the counterplay to somebody using one is to keep hitting them. Without it, a fight against somebody holding
 * two of them cannot be won by fighting — which is a change to what players experience, and nobody asked for
 * it.
 */
class TheMedikitTakesTimeTest {

    private static final UUID TRIBUTE = UUID.fromString("00000000-0000-0000-0000-00000000d0c1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000d0c2");

    /** Everything the countdown says and does, recorded rather than done. */
    private static final class Recorded implements MedikitCountdownService.Treatment {

        final List<String> said = new ArrayList<>();
        final Set<UUID> present = new HashSet<>(Set.of(TRIBUTE, OTHER));
        final List<UUID> healed = new ArrayList<>();
        /** Whether the holder still has a medikit when the treatment lands. */
        boolean stillHasOne = true;

        @Override
        public boolean stillThere(UUID holder) {
            return present.contains(holder);
        }

        @Override
        public void applied(UUID holder, int seconds) {
            said.add("applied:" + seconds);
        }

        @Override
        public void counting(UUID holder, int secondsLeft) {
            said.add("counting:" + secondsLeft);
        }

        @Override
        public void alreadyRunning(UUID holder) {
            said.add("already");
        }

        @Override
        public void interrupted(UUID holder) {
            said.add("interrupted");
        }

        @Override
        public boolean spendAndHeal(UUID holder) {
            said.add("healed");
            if (stillHasOne) {
                healed.add(holder);
            }
            return stillHasOne;
        }
    }

    private Recorded recorded;
    private MedikitCountdownService countdown;

    @BeforeEach
    void setUp() {
        recorded = new Recorded();
        countdown = new MedikitCountdownService(recorded, MedikitCountdownService.manual());
        countdown.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "medikitCountdownSeconds", 3));
    }

    @Nested
    @DisplayName("the wait")
    class TheWindUp {

        @Test
        @DisplayName("nothing is healed on the click")
        void notAtOnce() {
            countdown.begin(TRIBUTE);

            assertThat(recorded.healed)
                    .as("healing here is the regression: it makes the shop's most expensive item free")
                    .isEmpty();
            assertThat(countdown.isTreating(TRIBUTE)).isTrue();
        }

        @Test
        @DisplayName("three seconds later, it heals")
        void afterTheCount() {
            countdown.begin(TRIBUTE);

            countdown.tick();
            countdown.tick();
            assertThat(recorded.healed).as("still counting after two seconds").isEmpty();
            countdown.tick();

            assertThat(recorded.healed).containsExactly(TRIBUTE);
            assertThat(countdown.isTreating(TRIBUTE)).isFalse();
        }

        @Test
        @DisplayName("the count reads down, so it says how long is left rather than how long has gone")
        void whatTheyRead() {
            countdown.begin(TRIBUTE);
            countdown.tick();
            countdown.tick();
            countdown.tick();

            assertThat(recorded.said)
                    .containsExactly("applied:3", "counting:3", "counting:2", "counting:1", "healed");
        }

        @Test
        @DisplayName("a server that set the wait to zero gets an instant heal, and this never runs")
        void switchedOff() {
            countdown.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "medikitCountdownSeconds", 0));

            assertThat(countdown.begin(TRIBUTE))
                    .as("false means 'not answered here' — the caller heals on the spot, the source's own "
                            + "countdown <= 0 branch")
                    .isFalse();
            assertThat(recorded.said).isEmpty();
        }

        @Test
        @DisplayName("the wait comes from the settings, not from a number written in the code")
        void tuned() {
            countdown.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "medikitCountdownSeconds", 8));

            countdown.begin(TRIBUTE);

            assertThat(recorded.said).first().isEqualTo("applied:8");
        }
    }

    @Nested
    @DisplayName("being hit")
    class Interruption {

        @Test
        @DisplayName("any damage cancels it, and nothing is healed")
        void hitMeansNoHeal() {
            countdown.begin(TRIBUTE);
            countdown.tick();

            assertThat(countdown.interrupt(TRIBUTE)).isTrue();
            countdown.tick();
            countdown.tick();
            countdown.tick();

            assertThat(recorded.healed).isEmpty();
            assertThat(recorded.said).contains("interrupted");
        }

        @Test
        @DisplayName("being hit when nothing is being applied says nothing at all")
        void nothingToInterrupt() {
            assertThat(countdown.interrupt(TRIBUTE))
                    .as("a hit lands several times a second in a fight; a message per hit would be the "
                            + "whole chat")
                    .isFalse();
            assertThat(recorded.said).isEmpty();
        }

        @Test
        @DisplayName("one tribute's treatment is not cancelled by another one being hit")
        void notEachOthers() {
            countdown.begin(TRIBUTE);
            countdown.begin(OTHER);

            countdown.interrupt(OTHER);
            countdown.tick();
            countdown.tick();
            countdown.tick();

            assertThat(recorded.healed).containsExactly(TRIBUTE);
        }
    }

    @Nested
    @DisplayName("what it costs")
    class TheItem {

        @Test
        @DisplayName("the medikit is only spent when the treatment lands")
        void paidOnDelivery() {
            // The source's own note: "Verbraucht wird es deshalb erst beim Wirken." An interrupted
            // treatment must cost nothing, or the counterplay to a medikit is also a way to destroy one.
            countdown.begin(TRIBUTE);
            countdown.interrupt(TRIBUTE);

            assertThat(recorded.said).doesNotContain("healed");
        }

        @Test
        @DisplayName("somebody who no longer has one is not healed by it")
        void gaveItAway() {
            recorded.stillHasOne = false;
            countdown.begin(TRIBUTE);
            countdown.tick();
            countdown.tick();
            countdown.tick();

            assertThat(recorded.healed).isEmpty();
        }
    }

    @Nested
    @DisplayName("housekeeping")
    class NothingIsLeftBehind {

        @Test
        @DisplayName("a second click does not start a second treatment")
        void oneAtATime() {
            countdown.begin(TRIBUTE);
            countdown.begin(TRIBUTE);

            assertThat(recorded.said).containsExactly("applied:3", "counting:3", "already");
            assertThat(countdown.treating()).isEqualTo(1);
        }

        @Test
        @DisplayName("somebody who logged out is dropped rather than healed into an empty server")
        void goneMidTreatment() {
            countdown.begin(TRIBUTE);
            recorded.present.remove(TRIBUTE);

            countdown.tick();

            assertThat(recorded.healed).isEmpty();
            assertThat(countdown.isTreating(TRIBUTE)).isFalse();
        }

        @Test
        @DisplayName("the one shared timer runs exactly while somebody is waiting")
        void noTimerLeftTicking() {
            // The source started a BukkitRunnable per medikit, each of which had to remember to cancel
            // itself. Forty tributes in a deathmatch is forty timers, and the one that forgets is a task
            // ticking for somebody who logged out an hour ago.
            MedikitCountdownService real = new MedikitCountdownService(recorded, task -> () -> { });
            real.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "medikitCountdownSeconds", 2));

            assertThat(real.isTicking()).isFalse();
            real.begin(TRIBUTE);
            assertThat(real.isTicking()).isTrue();
            real.tick();
            real.tick();
            assertThat(real.isTicking())
                    .as("nobody is waiting any more, so nothing should still be ticking")
                    .isFalse();
        }

        @Test
        @DisplayName("forgetting somebody is silent — they are gone, not interrupted")
        void quietRemoval() {
            countdown.begin(TRIBUTE);
            recorded.said.clear();

            countdown.forget(TRIBUTE);

            assertThat(recorded.said).isEmpty();
            assertThat(countdown.isTreating(TRIBUTE)).isFalse();
        }
    }
}
