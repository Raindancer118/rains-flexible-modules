package de.raindancer.modules.hungergames;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the border, at its default ceiling, can be escaped by digging.
 *
 * <h2>The question this asks</h2>
 * A tribute caught on the wrong side of a closing border has two ways out: run, or dig. Running is
 * already accounted for — {@code border.max-edge-speed} exists so that a shrink is "never faster than
 * somebody can reasonably run from", and 2.5 blocks per second is comfortably under a sprint. Digging is
 * the case nobody sizes: a tribute in a stone hillside, with the iron pickaxe the arena's loot actually
 * hands out, who has to make a hole rather than take a corner.
 *
 * <p>So this works out how fast that person moves, from Minecraft's own numbers, and compares it with the
 * ceiling. It is arithmetic rather than a judgement, and it is written down here because the alternative
 * is somebody re-deriving it in a spreadsheet the next time the default is tuned.
 *
 * <h2>Why it is advisory rather than a build failure</h2>
 * Tagged {@code advisory} and run in a surefire execution that reports failures without failing the
 * build — see this module's pom. What the right ceiling is depends on the arena: a flat map where nobody
 * is ever walled in wants a faster border than a mountainous one, and a tournament may deliberately want
 * a border that <em>cannot</em> be dug away from, so that hiding in a hole is not a strategy. None of
 * those is wrong, so none of them should stop a release.
 *
 * <p>What would be wrong is changing the number without knowing which side of this line it lands on.
 * That is what the output of this test is for.
 */
@Tag("advisory")
class BorderOutrunTest {

    // ── Minecraft's numbers, not this plugin's ────────────────────────────────────────────────────

    /** Stone's block hardness. */
    private static final double STONE_HARDNESS = 1.5D;

    /** An iron pickaxe's mining-speed multiplier. */
    private static final double IRON_PICKAXE_SPEED = 6.0D;

    /**
     * How long one block of stone takes, with the right tool and no help.
     *
     * <p>{@code hardness × 1.5 ÷ tool speed}, which is the game's formula for a correct tool that can
     * harvest the block. No Efficiency, no Haste, not underwater and not in mid-air: a tribute has a
     * pickaxe out of a chest and nothing else, and every one of those modifiers would only ever make the
     * answer better than the one assumed here.
     */
    private static final double SECONDS_PER_BLOCK = STONE_HARDNESS * 1.5D / IRON_PICKAXE_SPEED;

    /**
     * Blocks broken per block of forward progress.
     *
     * <p>Two: the one at foot height and the one at head height. A one-high tunnel is not something a
     * player can walk down — the hitbox is 1.8 blocks tall and 1.5 even sneaking — so the tunnel that
     * actually moves somebody is two high, and it costs two blocks for every step it advances.
     */
    private static final int BLOCKS_PER_STEP = 2;

    /** How fast somebody digging a tunnel through stone actually travels, in blocks per second. */
    private static final double DIGGING_SPEED = 1.0D / (SECONDS_PER_BLOCK * BLOCKS_PER_STEP);

    @Test
    @DisplayName("the default border is slow enough to be dug away from with an iron pickaxe")
    void theBorderCanBeOutdug() {
        // The clamped accessor rather than the raw component: it is the number a round actually runs on.
        double ceiling = HungerGamesSettings.DEFAULTS.borderEdgeSpeed();

        assertThat(ceiling)
                .as("""
                        A tribute digging out through stone with an iron pickaxe advances at %.3f \
                        blocks per second: %.3f s per block, two blocks per step of a tunnel they can \
                        stand up in. The border's edge is allowed up to %.3f blocks per second.

                        Above that figure, digging is not an escape — the wall arrives while the second \
                        block of the current step is still being broken, and the only thing that saves \
                        anybody is having been somewhere diggable-free to begin with. Below it, a \
                        cornered tribute has a way out that costs them their pickaxe's durability and \
                        their position, which is the trade the border is meant to force.""",
                        DIGGING_SPEED, SECONDS_PER_BLOCK, ceiling)
                .isLessThanOrEqualTo(DIGGING_SPEED);
    }

    @Test
    @DisplayName("the arithmetic behind that figure")
    void theNumbersAreWhatTheyAreClaimedToBe() {
        // Not decoration. The test above fails or passes on DIGGING_SPEED, and if a constant here were
        // wrong it would fail or pass for a reason nobody could see. These are the two values somebody
        // would check against a wiki.
        assertThat(SECONDS_PER_BLOCK)
                .as("stone with an iron pickaxe, no Efficiency and no Haste")
                .isEqualTo(0.375D);
        assertThat(DIGGING_SPEED)
                .as("a two-high tunnel: 0.75 s of mining per block of progress")
                .isEqualTo(1.0D / 0.75D, org.assertj.core.api.Assertions.within(1e-9));
    }
}
