package de.raindancer.modules.homes;

import de.raindancer.modules.homes.rules.HomeLimitRule;
import de.raindancer.modules.homes.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many homes somebody may have.
 *
 * <h2>The bug this is checked against</h2>
 * The old plugin asked {@code hasPermission("homes.limit." + n)} for every n and kept the highest yes.
 * On Bukkit an <em>undeclared</em> permission defaults to true for an operator, so every operator
 * "held" {@code homes.limit.100} and was quietly given a hundred homes on a server configured for
 * three. The fix — reading what has actually been granted — now lives in Core's {@code NumberedLimit};
 * this rule is what asks it, and what adds the module's own two answers on top: the unlimited node,
 * and an operator counting as unlimited only when the owner said so.
 */
class HomeLimitRuleTest {

    private final HomeLimitRule rule = new HomeLimitRule();

    /** Somebody granted exactly these nodes and no others. */
    private static Set<String> granted(String... nodes) {
        return Set.of(nodes);
    }

    @Nested
    @DisplayName("the configured number")
    class Configured {

        @Test
        @DisplayName("somebody granted nothing gets what the config says")
        void nothingGranted() {
            assertThat(rule.limitFor(granted(), false, false, 3)).isEqualTo(3);
        }

        @Test
        @DisplayName("a numbered node raises it")
        void aNodeRaisesIt() {
            assertThat(rule.limitFor(granted("homes.limit.10"), false, false, 3)).isEqualTo(10);
        }

        @Test
        @DisplayName("a numbered node never lowers it")
        void aNodeNeverLowersIt() {
            assertThat(rule.limitFor(granted("homes.limit.1"), false, false, 3)).isEqualTo(3);
        }

        @Test
        @DisplayName("zero means homes are switched off, and that is a real answer")
        void zeroIsAnAnswer() {
            assertThat(rule.limitFor(granted(), false, false, 0)).isZero();
            assertThat(rule.isRoomFor(0, granted(), false, false, 0)).isFalse();
        }

        @Test
        @DisplayName("a node still works when the configured number is zero")
        void aNodeWorksAgainstZero() {
            // How a server switches homes off for everybody but one rank.
            assertThat(rule.limitFor(granted("homes.limit.5"), false, false, 0)).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("no limit")
    class Unlimited {

        @Test
        @DisplayName("the unlimited node beats every number")
        void theNodeWins() {
            assertThat(rule.isUnlimited(granted(PermissionNodes.UNLIMITED), false, false)).isTrue();
            assertThat(rule.limitFor(granted(PermissionNodes.UNLIMITED), false, false, 3))
                    .isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("an operator is not unlimited on their own")
        void anOperatorAloneIsNot() {
            // The deliberate change from the old plugin's earlier behaviour: an admin who silently
            // has more than everybody else is the one person who cannot test the limit.
            assertThat(rule.isUnlimited(granted(), true, false)).isFalse();
            assertThat(rule.limitFor(granted(), true, false, 3)).isEqualTo(3);
        }

        @Test
        @DisplayName("an operator is unlimited when the owner has said operators bypass")
        void anOperatorIsWhenTheOwnerSaysSo() {
            assertThat(rule.isUnlimited(granted(), true, true)).isTrue();
        }

        @Test
        @DisplayName("somebody who is not an operator is unaffected by that setting")
        void theSettingOnlyAffectsOperators() {
            assertThat(rule.isUnlimited(granted(), false, true)).isFalse();
        }

        @Test
        @DisplayName("it reads as a symbol rather than as two billion")
        void itReadsAsASymbol() {
            assertThat(rule.describeLimit(granted(PermissionNodes.UNLIMITED), false, false, 3))
                    .isEqualTo("∞");
            assertThat(rule.describeLimit(granted(), false, false, 3)).isEqualTo("3");
        }
    }

    @Nested
    @DisplayName("room for one more")
    class Room {

        @Test
        @DisplayName("under the limit there is room, at it there is not")
        void countingUp() {
            assertThat(rule.isRoomFor(2, granted(), false, false, 3)).isTrue();
            assertThat(rule.isRoomFor(3, granted(), false, false, 3)).isFalse();
        }

        @Test
        @DisplayName("unlimited always has room")
        void unlimitedAlwaysHasRoom() {
            assertThat(rule.isRoomFor(9_000, granted(PermissionNodes.UNLIMITED), false, false, 3))
                    .isTrue();
        }

        @Test
        @DisplayName("somebody over the limit already is refused a new one rather than crashing")
        void beingOverTheLimitIsSurvivable() {
            // Reachable in one step: the owner lowers the number in the config and everybody who had
            // five now has more than they are allowed. They keep them — see the note on replacing.
            assertThat(rule.isRoomFor(5, granted(), false, false, 3)).isFalse();
        }
    }

    @Nested
    @DisplayName("replacing one you already have")
    class Replacing {

        @Test
        @DisplayName("moving a home you already have is allowed at the limit")
        void atTheLimitYouMayStillMoveOne() {
            // The rule that stops a lowered limit trapping people. Without it, somebody with five
            // homes on a server that now allows three can neither move one nor — since moving is how
            // you fix a badly placed one — do anything but delete.
            assertThat(rule.mayReplace(5, granted(), false, false, 3)).isTrue();
        }

        @Test
        @DisplayName("moving one is allowed even when homes are switched off entirely")
        void evenAtZero() {
            assertThat(rule.mayReplace(2, granted(), false, false, 0)).isTrue();
        }
    }
}
