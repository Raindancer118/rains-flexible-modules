package de.raindancer.modules.xpbottle.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a bottle is, and the arithmetic it refuses to get wrong. */
class BottleTest {

    @Test
    @DisplayName("a plain bottle is level zero and cannot vacuum")
    void plainIsLevelZero() {
        Bottle plain = Bottle.empty(100);

        assertThat(plain.isPlain()).isTrue();
        assertThat(plain.mayVacuum()).isFalse();
        assertThat(plain.room()).isEqualTo(100);
    }

    @Test
    @DisplayName("a siphon bottle of any tier can vacuum")
    void siphonsVacuum() {
        assertThat(new Bottle(1, 0, 500).mayVacuum()).isTrue();
        assertThat(new Bottle(3, 0, 1500).mayVacuum()).isTrue();
        assertThat(new Bottle(3, 0, 1500).isPlain()).isFalse();
    }

    @Test
    @DisplayName("a bottle holding more than its tier now allows has no room, never negative room")
    void anOverfullBottleHasNoRoom() {
        // The owner lowered the capacity while this one was already sitting in somebody's chest.
        Bottle shrunk = new Bottle(1, 900, 500);

        assertThat(shrunk.room()).isZero();
        assertThat(shrunk.isFull()).isTrue();
        assertThat(shrunk.stored()).isEqualTo(900);
    }

    @Test
    @DisplayName("adding never puts in more than there is room for")
    void addingRespectsTheRoom() {
        Bottle nearlyFull = new Bottle(1, 480, 500);

        assertThat(nearlyFull.plus(100).stored()).isEqualTo(500);
        assertThat(nearlyFull.plus(10).stored()).isEqualTo(490);
        assertThat(nearlyFull.plus(-50).stored()).isEqualTo(480);
    }

    @Test
    @DisplayName("negative components are read as zero rather than stored")
    void nothingIsNegative() {
        Bottle nonsense = new Bottle(-2, -5, -10);

        assertThat(nonsense.level()).isZero();
        assertThat(nonsense.stored()).isZero();
        assertThat(nonsense.capacity()).isZero();
    }

    @Test
    @DisplayName("pouring one out leaves the same bottle, empty")
    void pouringEmptiesIt() {
        Bottle full = new Bottle(2, 1000, 1000);

        assertThat(full.poured().stored()).isZero();
        assertThat(full.poured().level()).isEqualTo(2);
        assertThat(full.poured().capacity()).isEqualTo(1000);
        assertThat(full.poured().isEmpty()).isTrue();
    }
}
