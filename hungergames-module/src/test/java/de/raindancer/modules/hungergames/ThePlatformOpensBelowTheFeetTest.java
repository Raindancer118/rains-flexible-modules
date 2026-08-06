package de.raindancer.modules.hungergames;

import de.raindancer.modules.hungergames.model.ArenaLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which block is taken out so a tribute can rise through their platform.
 *
 * <h2>The bug this was written for, seen on a live server</h2>
 * "The central blocks of the platforms are not being removed." They were — the wrong ones.
 *
 * <p>Read against the plugin this is ported from, the geometry is unambiguous. It pastes the tube at the
 * platform's position and the platform one block above it, teleports the arriving tribute to that position
 * plus one, and removes the centre block at the position <em>itself</em>. So the hole is one below where the
 * tribute ends up standing: the block they are lifted through, not the block they land on.
 *
 * <p>This port opened the block at the standing position instead. Which means the tribute levitates up the
 * tube and stops against the solid block immediately under their feet — the hole is above them, in the space
 * they were trying to reach, and the platform they are meant to arrive on has a gap in it.
 *
 * <h2>Why the answer lives on {@link ArenaLayout}</h2>
 * Because it is arithmetic about where things are, and every other such number in this module is there and
 * tested. It was a subtraction inlined in the service that does the block writes, which is the one place it
 * could not be checked without a server — and an off-by-one in a Y coordinate is invisible in a diff.
 */
class ThePlatformOpensBelowTheFeetTest {

    private static final int CENTRE_Y = 64;

    private static ArenaLayout aLayout() {
        return ArenaLayout.of("world", 0, CENTRE_Y, 0, 8, 12, HungerGamesSettings.DEFAULTS);
    }

    @Test
    @DisplayName("the hole is one block below where the tribute stands")
    void theHoleIsUnderneath() {
        ArenaLayout layout = aLayout();
        ArenaLayout.Stand platform = layout.platforms().get(0);

        assertThat(layout.wayUpThrough(platform))
                .as("the tribute is lifted through this block and lands on the one above it — opening the "
                        + "standing block leaves them stopped against the floor with a hole overhead")
                .isEqualTo(platform.blockY() - 1);
    }

    @Test
    @DisplayName("that block is the top of the tube, at ground level")
    void itIsTheTopOfTheTube() {
        ArenaLayout layout = aLayout();

        // The tube is pasted at ground level and the platform one above it, exactly as the source does. So
        // the block that has to move is where those two meet.
        assertThat(layout.wayUpThrough(layout.platforms().get(0)))
                .isEqualTo(layout.groundY());
    }

    @Test
    @DisplayName("the standing position is one above it, and stays solid")
    void theyLandOnSomething() {
        ArenaLayout layout = aLayout();

        assertThat(layout.standingY())
                .as("a platform with its own surface removed is one a tribute falls through")
                .isEqualTo(layout.wayUpThrough(layout.platforms().get(0)) + 1);
    }

    @Test
    @DisplayName("every platform answers for itself, not for the first one")
    void eachPlatformIsItsOwn() {
        ArenaLayout layout = aLayout();

        for (ArenaLayout.Stand platform : layout.platforms()) {
            assertThat(layout.wayUpThrough(platform))
                    .as("platform at X:%d Z:%d", platform.blockX(), platform.blockZ())
                    .isEqualTo(platform.blockY() - 1);
        }
        assertThat(layout.platforms()).hasSize(8);
    }
}
