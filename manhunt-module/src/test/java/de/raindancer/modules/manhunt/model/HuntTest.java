package de.raindancer.modules.manhunt.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules of a hunt in progress, with no server anywhere near them. */
class HuntTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());
    private static final UUID CARO = UUID.nameUUIDFromBytes("caro".getBytes());
    private static final UUID DAN = UUID.nameUUIDFromBytes("dan".getBytes());

    @Test
    @DisplayName("everybody racing who is not a Runner is a Hunter")
    void therestChase() {
        Hunt hunt = Hunt.of(Set.of(ANNA, BEN, CARO), Set.of(ANNA));

        assertThat(hunt.runners()).containsExactly(ANNA);
        assertThat(hunt.hunters()).containsExactlyInAnyOrder(BEN, CARO);
        assertThat(hunt.isHunter(BEN)).isTrue();
        assertThat(hunt.isRunner(BEN)).isFalse();
    }

    @Test
    @DisplayName("somebody on the Runner side who is not racing is in no hunt at all")
    void runnerWhoIsNotRacing() {
        Hunt hunt = Hunt.of(Set.of(ANNA, BEN), Set.of(ANNA, DAN));

        assertThat(hunt.runners()).containsExactly(ANNA);
        assertThat(hunt.everybody()).containsExactlyInAnyOrder(ANNA, BEN);
        assertThat(hunt.isHunter(DAN)).isFalse();
    }

    @Test
    @DisplayName("a death takes a Runner out of the hunt, once")
    void eliminationIsOnce() {
        Hunt hunt = Hunt.of(Set.of(ANNA, BEN, CARO), Set.of(ANNA, BEN));

        assertThat(hunt.eliminate(ANNA)).as("the first time").isTrue();
        assertThat(hunt.eliminate(ANNA)).as("a second death event for the same Runner").isFalse();
        assertThat(hunt.isEliminated(ANNA)).isTrue();
        assertThat(hunt.livingRunners()).containsExactly(BEN);
    }

    @Test
    @DisplayName("a Hunter dying eliminates nobody — they respawn and carry on")
    void huntersAreNotEliminated() {
        Hunt hunt = Hunt.of(Set.of(ANNA, CARO), Set.of(ANNA));

        assertThat(hunt.eliminate(CARO)).isFalse();
        assertThat(hunt.isEliminated(CARO)).isFalse();
        assertThat(hunt.allRunnersOut()).isFalse();
    }

    @Test
    @DisplayName("the Hunters have won once the last Runner is out, and not before")
    void allRunnersOut() {
        Hunt hunt = Hunt.of(Set.of(ANNA, BEN, CARO), Set.of(ANNA, BEN));

        hunt.eliminate(ANNA);
        assertThat(hunt.allRunnersOut()).isFalse();

        hunt.eliminate(BEN);
        assertThat(hunt.allRunnersOut()).isTrue();
        assertThat(hunt.livingRunners()).isEmpty();
    }
}
