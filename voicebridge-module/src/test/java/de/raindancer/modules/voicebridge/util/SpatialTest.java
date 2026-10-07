package de.raindancer.modules.voicebridge.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Hearing the world from one player's head: the same fade and panning Simple Voice Chat's client uses. */
class SpatialTest {

    private static final Spatial.Ear FACING_SOUTH = new Spatial.Ear(0, 64, 0, 0f);

    @Test
    @DisplayName("loudest at the speaker's feet, silent at the edge of the voice distance, linear between")
    void distanceFade() {
        assertThat(Spatial.distanceGain(0, 48)).isEqualTo(1f);
        assertThat(Spatial.distanceGain(24, 48)).isCloseTo(0.5f, within(1e-6f));
        assertThat(Spatial.distanceGain(48, 48)).isZero();
        assertThat(Spatial.distanceGain(100, 48)).as("beyond the edge is silence, not negative").isZero();
        assertThat(Spatial.distanceGain(5, 0)).as("a zero distance never divides").isZero();
    }

    @Test
    @DisplayName("straight ahead and straight behind are both ears equally")
    void centred() {
        float[] ahead = Spatial.ears(FACING_SOUTH, 0, 64, 10);
        float[] behind = Spatial.ears(FACING_SOUTH, 0, 64, -10);

        assertThat(ahead[0]).isCloseTo(ahead[1], within(1e-4f));
        assertThat(behind[0]).isCloseTo(behind[1], within(1e-4f));
        assertThat(ahead[0]).isCloseTo(1f, within(1e-4f));
    }

    @Test
    @DisplayName("facing south, west is on the right: a speaker there is louder in the right ear")
    void rightIsRight() {
        float[] west = Spatial.ears(FACING_SOUTH, -10, 64, 0);
        float[] east = Spatial.ears(FACING_SOUTH, 10, 64, 0);

        assertThat(west[1]).isGreaterThan(west[0]);
        assertThat(east[0]).isGreaterThan(east[1]);
        assertThat(west[1]).as("the near ear is at full volume").isCloseTo(1f, within(1e-4f));
        assertThat(west[0]).as("the far ear is never silent").isGreaterThanOrEqualTo(0.3f - 1e-4f);
    }

    @Test
    @DisplayName("turning round swaps the ears")
    void turning() {
        float[] facingSouth = Spatial.ears(FACING_SOUTH, -10, 64, 0);
        float[] facingNorth = Spatial.ears(new Spatial.Ear(0, 64, 0, 180f), -10, 64, 0);

        assertThat(facingNorth[0]).isCloseTo(facingSouth[1], within(1e-4f));
        assertThat(facingNorth[1]).isCloseTo(facingSouth[0], within(1e-4f));
    }

    @Test
    @DisplayName("a speaker far above or below pans less, as SVC does")
    void heightFlattensPanning() {
        float[] level = Spatial.ears(FACING_SOUTH, -10, 64, 0);
        float[] above = Spatial.ears(FACING_SOUTH, -10, 80, 0);

        assertThat(above[1] - above[0]).isLessThan(level[1] - level[0]);
    }

    @Test
    @DisplayName("somebody standing exactly where you are is centred, not NaN")
    void sameSpot() {
        float[] ears = Spatial.ears(FACING_SOUTH, 0, 64, 0);

        assertThat(ears[0]).isEqualTo(1f);
        assertThat(ears[1]).isEqualTo(1f);
    }

    @Test
    @DisplayName("mixing into a stereo frame: left and right scaled separately, summed, clipped")
    void mixesIntoStereo() {
        int[] stereo = new int[4];
        Spatial.addInto(stereo, new short[]{1000, -1000}, 0.5f, 1f);
        Spatial.addInto(stereo, new short[]{1000, -1000}, 0.5f, 1f);

        assertThat(stereo).containsExactly(1000, 2000, -1000, -2000);
        assertThat(Spatial.toBigEndian(new int[]{40000, -40000}))
                .as("clipped to 16 bit").containsExactly(0x7F, 0xFF, 0x80, 0x00);
    }
}
