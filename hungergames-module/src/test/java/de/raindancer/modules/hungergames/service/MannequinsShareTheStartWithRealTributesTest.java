package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.model.ArenaLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which platforms a rehearsal's mannequins take, when some tributes are real and online.
 *
 * <h2>The bug, reported from a live server</h2>
 * Mannequins spawned for a rehearsal were left standing wherever they had been spawned instead of on the
 * arena's platforms — but only when at least one real tribute was also online, which is exactly what
 * testing looks like: an admin, plus mannequins to fill out the round. {@code reachReady} placed mannequins
 * only inside {@code if (expected == 0)} — "nobody real came up a tube" — so a single online tribute
 * skipped the whole branch and every mannequin was ignored, not just the platforms the real tribute did not
 * need.
 *
 * <p>{@link StartupSequenceService#leftoverPlatforms} is the fix, pulled out on its own precisely so this
 * case is provable without a {@code World} or a {@code Player}: real tributes always fill platform indices
 * {@code [0, expected)} in {@code takeThemUnderground}'s own order, so whatever comes after is free for a
 * mannequin, whether that is every platform or none.
 */
class MannequinsShareTheStartWithRealTributesTest {

    private static ArenaLayout.Stand stand(double x) {
        return new ArenaLayout.Stand(x, 64, 0, 0f);
    }

    @Test
    @DisplayName("a real tribute online no longer starves every mannequin of a platform")
    void theBugItself() {
        // The exact shape reported: one real tribute (expected = 1) and platforms enough for more.
        List<ArenaLayout.Stand> platforms = List.of(stand(0), stand(1), stand(2), stand(3));

        List<ArenaLayout.Stand> leftover = StartupSequenceService.leftoverPlatforms(platforms, 1);

        assertThat(leftover)
                .as("the three platforms the online tribute did not take are exactly what mannequins should "
                        + "get, and the old code handed them none")
                .containsExactly(stand(1), stand(2), stand(3));
    }

    @Test
    @DisplayName("a pure rehearsal — nobody real online — still gets every platform")
    void pureRehearsal() {
        List<ArenaLayout.Stand> platforms = List.of(stand(0), stand(1));

        assertThat(StartupSequenceService.leftoverPlatforms(platforms, 0)).containsExactly(stand(0), stand(1));
    }

    @Test
    @DisplayName("every platform taken by real tributes leaves nothing for a mannequin")
    void noRoomLeft() {
        List<ArenaLayout.Stand> platforms = List.of(stand(0), stand(1));

        assertThat(StartupSequenceService.leftoverPlatforms(platforms, 2)).isEmpty();
    }

    @Test
    @DisplayName("more real tributes than platforms leaves nothing, and does not throw")
    void overfull() {
        List<ArenaLayout.Stand> platforms = List.of(stand(0));

        assertThat(StartupSequenceService.leftoverPlatforms(platforms, 5)).isEmpty();
    }
}
