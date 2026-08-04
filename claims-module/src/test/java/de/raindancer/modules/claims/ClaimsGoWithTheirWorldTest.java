package de.raindancer.modules.claims;

import de.raindancer.modules.claims.rules.WorldWasResetRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a claim in a world that was reset goes with it — and that nothing else ever does.
 *
 * <h2>What this is for</h2>
 * Asked for directly: "if I delete my world folder, these claims can go, because they are in the wrong
 * world." They are: the terrain is new, so a claim still sitting on those coordinates is protecting whatever
 * the generator happened to put there, for an owner who never chose it. Nobody would ever find it to remove
 * it by hand either, because the claim looks perfectly ordinary from every screen.
 *
 * <h2>Why the world's id is the whole answer</h2>
 * A claim already records the {@code UUID} of the world it is in, because it needs one to index by. That id
 * lives in {@code level.dat} and the server issues a <b>new</b> one when a world folder is deleted and
 * generated again. So a claim whose recorded id does not match the world now carrying that name is, exactly
 * and provably, a claim from a world that no longer exists — and there is nothing new to store, no timestamp
 * to keep and no bookkeeping that can drift.
 *
 * <h2>The guard that matters more than the feature</h2>
 * <b>A world that is not loaded is not a world that was reset.</b> Servers unload worlds — for maintenance,
 * on a multiverse setup, because a farm world is between regenerations — and a rule that read "no world of
 * that name right now" as "reset" would delete every claim in it the first time somebody did. That is
 * unrecoverable and it is the obvious way to write this, so it is the case tested hardest.
 */
class ClaimsGoWithTheirWorldTest {

    private static final UUID THE_WORLD_NOW = UUID.randomUUID();
    private static final UUID THE_WORLD_BEFORE = UUID.randomUUID();

    private final WorldWasResetRule rule = new WorldWasResetRule();

    /** What the server currently has loaded, by name. */
    private static Map<String, UUID> loaded(String name, UUID id) {
        return Map.of(name, id);
    }

    @Nested
    @DisplayName("a world that was reset")
    class WasReset {

        @Test
        @DisplayName("a claim whose world has a different id now is stale")
        void aNewIdMeansANewWorld() {
            assertThat(rule.wasReset("world", THE_WORLD_BEFORE, loaded("world", THE_WORLD_NOW)))
                    .as("the folder was deleted and generated again, so the terrain under this claim is "
                            + "not the terrain its owner claimed")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("everything that is not a reset")
    class NotAReset {

        @Test
        @DisplayName("the same id is the same world, however long ago the claim was made")
        void theSameIdIsTheSameWorld() {
            assertThat(rule.wasReset("world", THE_WORLD_NOW, loaded("world", THE_WORLD_NOW)))
                    .isFalse();
        }

        @Test
        @DisplayName("a world that is not loaded is left completely alone")
        void anUnloadedWorldIsNotAResetWorld() {
            // The one that would be unrecoverable. A server that unloads a world for an hour must not come
            // back to every claim in it deleted, and "there is no world of that name" is exactly what an
            // unloaded world and a deleted one both look like from here.
            assertThat(rule.wasReset("world", THE_WORLD_BEFORE, Map.of()))
                    .as("not loaded is not gone, and the difference is somebody's claims")
                    .isFalse();
            assertThat(rule.wasReset("world", THE_WORLD_BEFORE, loaded("somewhere-else", THE_WORLD_NOW)))
                    .isFalse();
        }

        @Test
        @DisplayName("a claim with no recorded world id is left alone")
        void anUnknownIdIsNotEvidence() {
            // An older claim, or one whose id could not be read. Absence of evidence is not evidence, and
            // the safe reading of "I do not know which world this was" is to keep it.
            assertThat(rule.wasReset("world", null, loaded("world", THE_WORLD_NOW))).isFalse();
        }

        @Test
        @DisplayName("a claim with no world name is left alone")
        void anUnknownNameIsNotEvidence() {
            assertThat(rule.wasReset(null, THE_WORLD_BEFORE, loaded("world", THE_WORLD_NOW))).isFalse();
            assertThat(rule.wasReset("  ", THE_WORLD_BEFORE, loaded("world", THE_WORLD_NOW))).isFalse();
        }

        @Test
        @DisplayName("nothing at all to compare against is left alone")
        void nothingKnownMeansNothingDone() {
            // What the very first tick after a failed startup looks like. A rule that deleted everything
            // when it knew nothing would be a rule that empties the store on exactly the worst day.
            assertThat(rule.wasReset("world", THE_WORLD_BEFORE, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("the shape of the answer")
    class Shape {

        @Test
        @DisplayName("it decides and does nothing else")
        void itIsARule() {
            // Asked once per claim at startup, on a store that may hold thousands. It takes plain values —
            // a name, an id and a map — so the decision that deletes somebody's claim is tested here rather
            // than tried once on a server.
            assertThat(rule.describe()).isNotBlank();
        }
    }
}
