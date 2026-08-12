package de.raindancer.modules.moderation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerMiningProfileTest {

    @Nested
    @DisplayName("before anything has been recorded")
    class Fresh {

        @Test
        @DisplayName("reads as entirely unremarkable, not unknown")
        void startsAtZero() {
            PlayerMiningProfile profile = new PlayerMiningProfile();

            assertThat(profile.oreRatio()).isZero();
            assertThat(profile.approachDirectness()).isZero();
            assertThat(profile.observedOre()).isZero();
            assertThat(profile.probabilityPercent(50))
                    .as("nobody with no evidence against them yet should read as clean, the same "
                            + "as somebody who has been checked and found clean")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("settling towards the truth")
    class Settling {

        @Test
        @DisplayName("mining nothing but ore settles the ratio near one")
        void settlesHighOnAllOre() {
            PlayerMiningProfile profile = new PlayerMiningProfile();
            for (int i = 0; i < 2000; i++) {
                profile.recordBlock(true, i);
            }
            assertThat(profile.oreRatio()).isGreaterThan(0.95);
        }

        @Test
        @DisplayName("mining nothing but stone settles the ratio near zero")
        void settlesLowOnAllStone() {
            PlayerMiningProfile profile = new PlayerMiningProfile();
            for (int i = 0; i < 2000; i++) {
                profile.recordBlock(false, i);
            }
            assertThat(profile.oreRatio()).isLessThan(0.05);
        }

        @Test
        @DisplayName("a straight approach mined over and over settles the directness near a hundred")
        void settlesHighOnDirectApproaches() {
            PlayerMiningProfile profile = new PlayerMiningProfile();
            for (int i = 0; i < 500; i++) {
                profile.recordApproach(100, i);
            }
            assertThat(profile.approachDirectness()).isGreaterThan(95.0);
        }

        @Test
        @DisplayName("recent behaviour outweighs old behaviour, so a rough patch can be lived down")
        void oldBehaviourFades() {
            PlayerMiningProfile profile = new PlayerMiningProfile();
            for (int i = 0; i < 200; i++) {
                profile.recordBlock(true, i);
            }
            double afterTheRoughPatch = profile.oreRatio();
            for (int i = 0; i < 2000; i++) {
                profile.recordBlock(false, i);
            }
            assertThat(profile.oreRatio())
                    .as("a spike from long ago should not still be inflating the score today")
                    .isLessThan(afterTheRoughPatch);
        }

        @Test
        @DisplayName("each recorded approach counts towards how many ore blocks have been observed")
        void countsObservedOre() {
            PlayerMiningProfile profile = new PlayerMiningProfile();
            profile.recordApproach(80, 0);
            profile.recordApproach(20, 1);

            assertThat(profile.observedOre()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("the combined probability")
    class Probability {

        @Test
        @DisplayName("a ratio sitting exactly at the threshold, with no approach data, reads as half")
        void ratioAtThresholdWithNoApproachIsHalf() {
            PlayerMiningProfile profile = new PlayerMiningProfile();
            for (int i = 0; i < 2000; i++) {
                // Settles the ratio near 0.5, which at a 50% threshold is exactly "at it".
                profile.recordBlock(i % 2 == 0, i);
            }
            assertThat(profile.probabilityPercent(50))
                    .as("the ratio signal alone reads a hundred at the threshold; averaged with an "
                            + "untouched, zero approach signal, that is fifty")
                    .isCloseTo(50, org.assertj.core.data.Offset.offset(3));
        }

        @Test
        @DisplayName("both signals maxed out reads as a hundred, never higher")
        void bothSignalsMaxedReadsAHundred() {
            PlayerMiningProfile profile = new PlayerMiningProfile();
            for (int i = 0; i < 2000; i++) {
                profile.recordBlock(true, i);
                profile.recordApproach(100, i);
            }
            assertThat(profile.probabilityPercent(50)).isEqualTo(100);
        }

        @Test
        @DisplayName("a ratio far past the threshold is clamped rather than pushing the score past a hundred")
        void ratioPastThresholdIsClamped() {
            PlayerMiningProfile profile = new PlayerMiningProfile();
            for (int i = 0; i < 2000; i++) {
                profile.recordBlock(true, i);
            }
            // A ratio near 1.0 (100%) is many times a threshold this low.
            assertThat(profile.probabilityPercent(1)).isLessThanOrEqualTo(100);
        }

        @Test
        @DisplayName("a threshold of zero never divides by it")
        void zeroThresholdIsHarmless() {
            PlayerMiningProfile profile = new PlayerMiningProfile();
            profile.recordBlock(true, 0);

            assertThat(profile.probabilityPercent(0)).isBetween(0, 100);
        }
    }

    @Test
    @DisplayName("reading back exactly what was written, for the persisted constructor")
    void roundTripsThroughTheStorageConstructor() {
        PlayerMiningProfile profile = new PlayerMiningProfile(0.3, 42.0, 7, 123456789L);

        assertThat(profile.oreRatio()).isEqualTo(0.3);
        assertThat(profile.approachDirectness()).isEqualTo(42.0);
        assertThat(profile.observedOre()).isEqualTo(7);
        assertThat(profile.lastUpdatedEpochMillis()).isEqualTo(123456789L);
    }
}
