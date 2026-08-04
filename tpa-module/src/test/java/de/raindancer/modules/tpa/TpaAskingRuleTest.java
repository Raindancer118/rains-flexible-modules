package de.raindancer.modules.tpa;

import de.raindancer.modules.tpa.model.TpaPrefs;
import de.raindancer.modules.tpa.rules.TpaAskingRule;
import de.raindancer.modules.tpa.rules.TpaAskingRule.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether one player may ask another.
 *
 * <h2>Why the answers are named rather than a boolean</h2>
 * Because "they are in another world", "they are not accepting", "you have already asked them" and
 * "wait five seconds" are four different things to tell somebody, and a silent refusal is a command
 * people type four more times. The wording is the module's; which of the four applies is here.
 *
 * <h2>The one refusal that is deliberately a lie by omission</h2>
 * Being blocked and having requests switched off give the <em>same</em> answer. Telling somebody they
 * have been blocked turns a quiet decision into a confrontation — which is exactly what the person who
 * blocked them was avoiding.
 */
class TpaAskingRuleTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private final TpaAskingRule rule = new TpaAskingRule();

    @Nested
    @DisplayName("when it is fine")
    class Allowed {

        @Test
        @DisplayName("an ordinary request between two people in one world")
        void ordinary() {
            assertThat(rule.check(ALICE, BOB, TpaPrefs.untouched(), true, false, false, false))
                    .isEqualTo(Verdict.FINE);
        }

        @Test
        @DisplayName("across worlds, when the server allows it")
        void acrossWorldsWhenAllowed() {
            // "Reachable" is one fact to the rule and two to the caller: in the same world, or in
            // another one the server lets requests cross. The refusal is the same sentence either
            // way, so the rule is not told which.
            assertThat(rule.check(ALICE, BOB, TpaPrefs.untouched(), true, false, false, false))
                    .isEqualTo(Verdict.FINE);
        }
    }

    @Nested
    @DisplayName("when it is not")
    class Refused {

        @Test
        @DisplayName("asking yourself")
        void yourself() {
            assertThat(rule.check(ALICE, ALICE, TpaPrefs.untouched(), true, false, false, false))
                    .isEqualTo(Verdict.YOURSELF);
        }

        @Test
        @DisplayName("across worlds when the server forbids it")
        void acrossWorlds() {
            assertThat(rule.check(ALICE, BOB, TpaPrefs.untouched(), false, false, false, false))
                    .isEqualTo(Verdict.ANOTHER_WORLD);
        }

        @Test
        @DisplayName("somebody who has requests switched off")
        void switchedOff() {
            assertThat(rule.check(ALICE, BOB, TpaPrefs.untouched().refusingEverybody(),
                    true, false, false, false))
                    .isEqualTo(Verdict.NOT_ACCEPTING);
        }

        @Test
        @DisplayName("somebody who has blocked you — with the same answer, on purpose")
        void blocked() {
            // The whole point. A different answer here would tell the asker they have been blocked,
            // which turns a quiet decision into a confrontation.
            assertThat(rule.check(ALICE, BOB, TpaPrefs.untouched().blocking(ALICE),
                    true, false, false, false))
                    .as("being blocked has to be indistinguishable from being switched off")
                    .isEqualTo(Verdict.NOT_ACCEPTING);
        }

        @Test
        @DisplayName("asking somebody you have already asked")
        void alreadyAsked() {
            assertThat(rule.check(ALICE, BOB, TpaPrefs.untouched(), true, false, true, false))
                    .isEqualTo(Verdict.ALREADY_ASKED);
        }

        @Test
        @DisplayName("asking again too soon")
        void tooSoon() {
            assertThat(rule.check(ALICE, BOB, TpaPrefs.untouched(), true, false, false, true))
                    .isEqualTo(Verdict.TOO_SOON);
        }
    }

    @Nested
    @DisplayName("the bypass")
    class Bypassing {

        @Test
        @DisplayName("it gets past somebody who is switched off")
        void pastTheToggle() {
            assertThat(rule.check(ALICE, BOB, TpaPrefs.untouched().refusingEverybody(),
                    true, true, false, false))
                    .isEqualTo(Verdict.FINE);
        }

        @Test
        @DisplayName("it gets past a block")
        void pastABlock() {
            assertThat(rule.check(ALICE, BOB, TpaPrefs.untouched().blocking(ALICE),
                    true, true, false, false))
                    .isEqualTo(Verdict.FINE);
        }

        @Test
        @DisplayName("it does not get past asking yourself")
        void notPastYourself() {
            // Not a permission question. There is nowhere to go.
            assertThat(rule.check(ALICE, ALICE, TpaPrefs.untouched(), true, true, false, false))
                    .isEqualTo(Verdict.YOURSELF);
        }

        @Test
        @DisplayName("it does not get past having already asked them")
        void notPastAskingTwice() {
            // Also not a permission question: the answer they have not given yet is still coming.
            assertThat(rule.check(ALICE, BOB, TpaPrefs.untouched(), true, true, true, false))
                    .isEqualTo(Verdict.ALREADY_ASKED);
        }
    }

    @Nested
    @DisplayName("the order the refusals are asked in")
    class Order {

        @Test
        @DisplayName("the wait is asked last, so a refused request costs nothing")
        void theWaitIsLast() {
            // Asking it first means a typo, or somebody who is not accepting, costs five seconds of
            // waiting for a request that was never going to be made.
            assertThat(rule.check(ALICE, BOB, TpaPrefs.untouched().refusingEverybody(),
                    true, false, false, true))
                    .as("they should hear why it will never work, not that they must wait to be "
                            + "told again")
                    .isEqualTo(Verdict.NOT_ACCEPTING);
        }

        @Test
        @DisplayName("asking yourself is caught before anything about the other person")
        void yourselfIsFirst() {
            assertThat(rule.check(ALICE, ALICE, TpaPrefs.untouched().refusingEverybody(),
                    false, false, true, true))
                    .isEqualTo(Verdict.YOURSELF);
        }
    }

    @Nested
    @DisplayName("saying why")
    class Wording {

        @Test
        @DisplayName("every refusal names a message, and the good one names none")
        void everyRefusalCanBeSaid() {
            for (Verdict verdict : Verdict.values()) {
                if (verdict == Verdict.FINE) {
                    assertThat(verdict.messageKey()).isNull();
                    continue;
                }
                assertThat(verdict.messageKey())
                        .as("%s has no wording, so the refusal would be silent — and a silent "
                                + "refusal is a command people type four more times", verdict)
                        .startsWith("tpa.");
            }
        }

        @Test
        @DisplayName("being blocked and being switched off are one verdict, so they cannot drift apart")
        void oneVerdictForBoth() {
            // Two verdicts with the same wording today is two verdicts with different wording after
            // somebody edits one of them.
            assertThat(Verdict.values())
                    .as("there is exactly one 'not accepting' answer")
                    .filteredOn(verdict -> verdict.name().contains("BLOCK"))
                    .isEmpty();
        }
    }
}
