package de.raindancer.modules.farmworld;

import de.raindancer.modules.farmworld.rules.NoticeRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the server is told a farm world is about to be thrown away.
 *
 * <h2>Why this is tested and not tried</h2>
 * Because trying it means waiting for a real countdown, and the failures worth catching are all at the edges of
 * one: the same notice going out every twenty seconds for five minutes, a notice going out again after a
 * restart, and — the one that actually matters — no notice at all because something earlier had already been
 * said. The last of those is silent, and the complaint it produces is "the farm world was wiped without
 * warning", which is exactly the complaint this feature exists to answer.
 */
class NoticeRuleTest {

    private static final Duration FIFTEEN = Duration.ofMinutes(15);
    private static final Duration FIVE = Duration.ofMinutes(5);
    private static final Duration ONE = Duration.ofMinutes(1);

    private final NoticeRule rule = new NoticeRule(FIFTEEN);

    @Nested
    @DisplayName("which notices exist")
    class Leads {

        @Test
        @DisplayName("the owner's, then the two that always go out, longest first")
        void theOwnersAndTheFixedOnes() {
            assertThat(rule.leads()).containsExactly(FIFTEEN, FIVE, ONE);
        }

        @Test
        @DisplayName("zero leaves the two that always go out")
        void zeroIsNotSilence() {
            // An owner who sets this to zero has said "do not warn an hour ahead", not "let three worlds
            // disappear under people with nothing said at all".
            assertThat(new NoticeRule(Duration.ZERO).leads()).containsExactly(FIVE, ONE);
            assertThat(new NoticeRule(null).leads()).containsExactly(FIVE, ONE);
        }

        @Test
        @DisplayName("an owner's lead that is one of the fixed ones is not announced twice")
        void nothingIsSaidTwiceInTheSameSecond() {
            assertThat(new NoticeRule(FIVE).leads()).containsExactly(FIVE, ONE);
        }

        @Test
        @DisplayName("a lead shorter than a fixed one drops the ones it is shorter than")
        void nothingFiresAfterTheThingItWarnsAbout() {
            // A five-minute notice on a farm world warned about two minutes ahead would fire on the same tick
            // as the regeneration, which is a line nobody can act on.
            assertThat(new NoticeRule(Duration.ofMinutes(2)).leads())
                    .containsExactly(Duration.ofMinutes(2), ONE);
            assertThat(new NoticeRule(Duration.ofSeconds(30)).leads())
                    .containsExactly(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("a lead longer than a day is brought back to one")
        void anAbsurdLeadIsClamped() {
            assertThat(new NoticeRule(Duration.ofDays(40)).leads().getFirst())
                    .as("a notice given a month ahead is a notice nobody remembers hearing")
                    .isEqualTo(NoticeRule.LONGEST);
        }
    }

    @Nested
    @DisplayName("which one is due right now")
    class DueNow {

        @Test
        @DisplayName("nothing while there is more time left than the longest notice")
        void quietUntilTheFirstLead() {
            assertThat(rule.dueNow(Duration.ofHours(3), null)).isEmpty();
            assertThat(rule.dueNow(Duration.ofMinutes(16), null)).isEmpty();
        }

        @Test
        @DisplayName("the owner's notice as soon as the time left falls inside it")
        void theFirstOne() {
            assertThat(rule.dueNow(FIFTEEN, null)).contains(FIFTEEN);
            assertThat(rule.dueNow(Duration.ofMinutes(14), null)).contains(FIFTEEN);
        }

        @Test
        @DisplayName("nothing again until the next one, however often it is asked")
        void oneNoticeAndNotAHundred() {
            // The timer asks every twenty seconds. Without this, a fifteen-minute notice is fifteen minutes of
            // the same line every twenty seconds, which is the version of this feature people switch off.
            assertThat(rule.dueNow(Duration.ofMinutes(14), FIFTEEN)).isEmpty();
            assertThat(rule.dueNow(Duration.ofMinutes(6), FIFTEEN)).isEmpty();
        }

        @Test
        @DisplayName("then the five-minute one, then the one-minute one")
        void eachOneInTurn() {
            assertThat(rule.dueNow(FIVE, FIFTEEN)).contains(FIVE);
            assertThat(rule.dueNow(Duration.ofMinutes(3), FIVE)).isEmpty();
            assertThat(rule.dueNow(ONE, FIVE)).contains(ONE);
            assertThat(rule.dueNow(Duration.ofSeconds(30), ONE)).isEmpty();
        }

        @Test
        @DisplayName("a notice missed entirely is not skipped over in silence")
        void aMissedLeadFallsThroughToTheNextOne() {
            // The server was busy, or was restarted, and by the first look there are ninety seconds left with
            // nothing said. Saying nothing because the fifteen-minute notice is now unreachable would be the
            // one failure this whole feature exists to prevent.
            assertThat(rule.dueNow(Duration.ofSeconds(90), null))
                    .as("the five-minute notice is the honest thing to say when the moment has passed")
                    .contains(FIVE);
        }

        @Test
        @DisplayName("nothing once it is due or past")
        void nothingToWarnAboutWhenItIsHappening() {
            // Null is what "due now" looks like. The thing being warned about is already happening, and a
            // warning then would go out every twenty seconds for as long as a failed regeneration stayed due.
            assertThat(rule.dueNow(null, null)).isEmpty();
            assertThat(rule.dueNow(Duration.ZERO, null)).isEmpty();
            assertThat(rule.dueNow(Duration.ofSeconds(-10), null)).isEmpty();
        }

        @Test
        @DisplayName("a rule with no notices at all never says anything")
        void anEmptyRuleIsQuiet() {
            NoticeRule none = new NoticeRule(Duration.ofSeconds(1));

            Optional<Duration> due = none.dueNow(Duration.ofSeconds(2), null);
            assertThat(due).isEmpty();
        }
    }

    @Nested
    @DisplayName("when a countdown has started over")
    class StartingOver {

        @Test
        @DisplayName("more time left than the longest notice means a new countdown")
        void moreTimeThanTheLongestLead() {
            assertThat(rule.hasStartedOver(Duration.ofHours(2))).isTrue();
            assertThat(rule.hasStartedOver(Duration.ofMinutes(16))).isTrue();
        }

        @Test
        @DisplayName("still inside the notices is the same countdown")
        void insideTheLeadsIsTheSameOne() {
            assertThat(rule.hasStartedOver(FIFTEEN)).isFalse();
            assertThat(rule.hasStartedOver(ONE)).isFalse();
        }

        @Test
        @DisplayName("due now is not a new countdown")
        void dueIsNotFresh() {
            // Treated as fresh, a farm world that stayed due — a regeneration that could not be completed —
            // would have its notices forgotten and re-announced on every look.
            assertThat(rule.hasStartedOver(null)).isFalse();
        }
    }
}
