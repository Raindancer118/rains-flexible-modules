package de.raindancer.modules.farmworld;

import de.raindancer.core.world.farm.WorldSet;
import de.raindancer.modules.farmworld.model.FarmWorldView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One farm world as everything outside RainsCore sees it.
 *
 * <p>Small, and worth having: "how long has it got left" is the one number on the page nobody may be shown
 * wrongly, and there are three states of it that read identically if the arithmetic is careless — a schedule with
 * time left, a schedule that is up, and no schedule at all.
 */
class FarmWorldViewTest {

    private static final WorldSet WEEKLY = WorldSet.builder("mining")
            .every(Duration.ofDays(7)).border(5000).build();
    private static final WorldSet FOR_EVER = WorldSet.of("keeper");

    @Test
    @DisplayName("a farm world with time left says how much, and is not due")
    void timeLeft() {
        FarmWorldView view = new FarmWorldView(WEEKLY, true, Duration.ofHours(3));

        assertThat(view.untilRegenerated()).contains(Duration.ofHours(3));
        assertThat(view.isDue()).isFalse();
        assertThat(view.isScheduled()).isTrue();
        assertThat(view.every()).contains(Duration.ofDays(7));
    }

    @Test
    @DisplayName("a scheduled farm world with no time left is due")
    void dueNow() {
        FarmWorldView view = new FarmWorldView(WEEKLY, true, null);

        assertThat(view.untilRegenerated()).isEmpty();
        assertThat(view.isDue()).isTrue();
    }

    @Test
    @DisplayName("a farm world with no schedule is never due, however long it has been there")
    void neverDue() {
        // The distinction the null carries. Without it, a farm world kept deliberately would be drawn as "due to
        // be thrown away" for ever — which is the one sentence that decides whether somebody builds there.
        FarmWorldView view = new FarmWorldView(FOR_EVER, true, null);

        assertThat(view.isScheduled()).isFalse();
        assertThat(view.isDue()).isFalse();
        assertThat(view.every()).isEmpty();
    }

    @Test
    @DisplayName("a time already past is carried as none, never as a negative")
    void nothingIsNegative() {
        // Otherwise the one place people look to plan a trip reads "made again in -3 minutes".
        FarmWorldView view = new FarmWorldView(WEEKLY, true, Duration.ofMinutes(-3));

        assertThat(view.untilRegenerated()).isEmpty();
        assertThat(view.isDue()).isTrue();
        assertThat(new FarmWorldView(WEEKLY, true, Duration.ZERO).untilRegenerated()).isEmpty();
    }

    @Test
    @DisplayName("the worlds and the border come straight from Core's own value")
    void itDelegatesRatherThanCopying() {
        // Kept whole rather than copied field by field, so a component added to WorldSet does not have to be
        // added here to be reachable.
        FarmWorldView view = new FarmWorldView(WEEKLY, true, null);

        assertThat(view.name()).isEqualTo("mining");
        assertThat(view.worlds()).containsExactly("mining", "mining_nether", "mining_the_end");
        assertThat(view.border()).contains(5000);
        assertThat(view.hasNether()).isTrue();
        assertThat(view.hasEnd()).isTrue();
    }

    @Test
    @DisplayName("a farm world whose worlds are not loaded is an ordinary state, not a broken one")
    void notLoadedIsFine() {
        FarmWorldView view = new FarmWorldView(WEEKLY, false, Duration.ofDays(1));

        assertThat(view.loaded()).isFalse();
        assertThat(view.untilRegenerated()).contains(Duration.ofDays(1));
    }

    @Test
    @DisplayName("there is no view without a farm world")
    void theSetIsRequired() {
        assertThatThrownBy(() -> new FarmWorldView(null, true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
