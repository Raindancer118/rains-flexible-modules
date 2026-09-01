package de.raindancer.modules.xpbottle.rules;

import de.raindancer.modules.xpbottle.model.Bottle;
import de.raindancer.modules.xpbottle.model.Bottling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The one sum this module cannot get wrong. */
class FillAmountRuleTest {

    private final FillAmountRule rule = new FillAmountRule();

    @Test
    @DisplayName("everything moves when the bottle is big enough")
    void everythingMoves() {
        Bottling filling = rule.moved(70, Bottle.empty(100));

        assertThat(filling.moved()).isEqualTo(70);
        assertThat(filling.bottle().stored()).isEqualTo(70);
        assertThat(filling.reason()).isEqualTo(Bottling.Reason.FILLED);
    }

    @Test
    @DisplayName("only what fits moves, and the rest is left where it was")
    void onlyWhatFitsMoves() {
        Bottling filling = rule.moved(400, new Bottle(0, 60, 100));

        assertThat(filling.moved()).isEqualTo(40);
        assertThat(filling.bottle().isFull()).isTrue();
    }

    @Test
    @DisplayName("a full bottle is reported as full even when there was nothing to take either")
    void fullBeatsEmptyHanded() {
        Bottling filling = rule.moved(0, new Bottle(1, 500, 500));

        assertThat(filling.happened()).isFalse();
        assertThat(filling.reason()).isEqualTo(Bottling.Reason.ALREADY_FULL);
    }

    @Test
    @DisplayName("a player with no experience is told that, not that the bottle is full")
    void nothingToTakeIsItsOwnAnswer() {
        Bottling filling = rule.moved(0, Bottle.empty(100));

        assertThat(filling.reason()).isEqualTo(Bottling.Reason.NOTHING_TO_TAKE);
        assertThat(filling.bottle().stored()).isZero();
    }

    @Test
    @DisplayName("the siphon's per-tick ceiling caps a go without capping the bottle")
    void theCeilingCapsOneGo() {
        Bottling filling = rule.movedAtMost(10_000, Bottle.empty(1000), 40);

        assertThat(filling.moved()).isEqualTo(40);
        assertThat(filling.bottle().stored()).isEqualTo(40);
        assertThat(filling.bottle().isFull()).isFalse();
    }

    @Test
    @DisplayName("a ceiling of zero or less moves nothing rather than emptying the source")
    void aZeroCeilingMovesNothing() {
        assertThat(rule.movedAtMost(500, Bottle.empty(1000), 0).happened()).isFalse();
        assertThat(rule.movedAtMost(500, Bottle.empty(1000), -3).happened()).isFalse();
    }

    @Test
    @DisplayName("negative experience is read as none, not as a debt the bottle pays")
    void negativeAvailableTakesNothing() {
        Bottling filling = rule.moved(-200, Bottle.empty(100));

        assertThat(filling.moved()).isZero();
        assertThat(filling.reason()).isEqualTo(Bottling.Reason.NOTHING_TO_TAKE);
    }

    @Test
    @DisplayName("no bottle at all is a full one, not a crash")
    void noBottleIsRefusedPolitely() {
        assertThat(rule.moved(50, null).happened()).isFalse();
    }
}
