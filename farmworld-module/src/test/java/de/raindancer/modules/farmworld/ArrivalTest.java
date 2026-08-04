package de.raindancer.modules.farmworld;

import de.raindancer.modules.farmworld.model.Arrival;
import de.raindancer.modules.farmworld.model.Scatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of the two ways into a farm world a trip is asking for.
 *
 * <h2>Why the default is the spawn and not a random point</h2>
 * Scattering everybody was the first design, and it is wrong as a default for a reason that only shows up
 * once you are standing in it: a random point is a place with no way back and nothing around it. A farm
 * world's spawn is where an admin builds the hub, the portals and the sign saying when it is regenerated —
 * and dropping people four thousand blocks from all of that means none of it is ever seen.
 *
 * <p>So the plain trip is predictable, and going somewhere nobody has been is a thing you <em>ask</em> for.
 * The scattering itself is unchanged and still what makes the far parts of a farm world worth having; it is
 * only no longer what happens when you did not ask.
 */
class ArrivalTest {

    @Nested
    @DisplayName("what a plain trip means")
    class ByDefault {

        @Test
        @DisplayName("a trip with no word after it goes to the spawn")
        void theDefaultIsTheSpawn() {
            assertThat(Arrival.of(null)).isEqualTo(Arrival.SPAWN);
            assertThat(Arrival.of("")).isEqualTo(Arrival.SPAWN);
            assertThat(Arrival.of("   ")).isEqualTo(Arrival.SPAWN);
        }

        @Test
        @DisplayName("the spawn arrival does not scatter, whatever the settings say")
        void theSpawnIsNeverRandom() {
            assertThat(Arrival.SPAWN.isScattered()).isFalse();
        }
    }

    @Nested
    @DisplayName("asking to be sent somewhere nobody has been")
    class Wild {

        @Test
        @DisplayName("the words somebody would actually type")
        void theWordsThatMeanWild() {
            for (String word : java.util.List.of("wild", "random", "rtp", "scatter",
                    "WILD", "Random")) {
                assertThat(Arrival.of(word))
                        .as("%s should ask to be scattered", word)
                        .isEqualTo(Arrival.WILD);
            }
        }

        @Test
        @DisplayName("anything else is not a way of asking, so it stays the spawn")
        void anythingElseIsTheSpawn() {
            // A typo must not scatter somebody. Landing four thousand blocks out because "wilf" was read as
            // a request is a mistake that costs a walk home, and the safe reading of an unknown word is the
            // one that puts them somewhere with a way back.
            assertThat(Arrival.of("wilf")).isEqualTo(Arrival.SPAWN);
            assertThat(Arrival.of("confirm")).isEqualTo(Arrival.SPAWN);
        }

        @Test
        @DisplayName("wild scatters")
        void wildIsRandom() {
            assertThat(Arrival.WILD.isScattered()).isTrue();
        }
    }

    @Nested
    @DisplayName("what the server allows")
    class WhatTheServerAllows {

        @Test
        @DisplayName("asking to be scattered is refused when the server has it switched off")
        void offMeansOff() {
            // scatter-arrivals is now "may people ask to be sent into the wild" rather than "is every
            // arrival random". A server with it off has a farm world people walk out of, and the word has to
            // be refused rather than quietly ignored — a command that silently does something else is one
            // people type four more times.
            assertThat(Arrival.WILD.isAllowedBy(new Scatter(false, 250, 4000))).isFalse();
            assertThat(Arrival.WILD.isAllowedBy(Scatter.NOWHERE)).isFalse();
        }

        @Test
        @DisplayName("and allowed when it is on")
        void onMeansOn() {
            assertThat(Arrival.WILD.isAllowedBy(new Scatter(true, 250, 4000))).isTrue();
        }

        @Test
        @DisplayName("going to the spawn is always allowed, whatever the scatter settings say")
        void theSpawnIsAlwaysAvailable() {
            // Otherwise switching scattering off would switch the farm world off, which is the opposite of
            // what an owner asking for predictable arrivals wants.
            assertThat(Arrival.SPAWN.isAllowedBy(Scatter.NOWHERE)).isTrue();
            assertThat(Arrival.SPAWN.isAllowedBy(new Scatter(false, 250, 4000))).isTrue();
        }
    }
}
