package de.raindancer.modules.cosmetics;

import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.store.WingReservations;
import de.raindancer.modules.cosmetics.store.WingReservations.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An op keeps a combination of wings — their kind and colours — to themselves: nobody else may wear
 * exactly those, and the reservation outlives restarts.
 */
class WingReservationsTest {

    private static final UUID LILLY = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEN = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static final ParticleChoice PLUM = new ParticleChoice("DUST", ParticleShape.GRAND_WINGS, 0x3B1A2F)
            .withColourTo(0x5C203E);

    @TempDir
    Path directory;

    private WingReservations open() {
        WingReservations reservations = new WingReservations(directory.resolve("wing-reservations.yml"));
        reservations.load();
        return reservations;
    }

    @Test
    @DisplayName("reserved, the combination is its owner's alone")
    void reserving() {
        WingReservations reservations = open();

        assertThat(reservations.reserve(LILLY, "Lilly", PLUM)).isEqualTo(Outcome.RESERVED);

        assertThat(reservations.mayWear(LILLY, PLUM)).isTrue();
        assertThat(reservations.mayWear(BEN, PLUM)).isFalse();
        assertThat(reservations.holderOf(PLUM).orElseThrow().ownerName()).isEqualTo("Lilly");
    }

    @Test
    @DisplayName("the same colours in other wings, other colours, the gradient the other way, or another dust — all still free")
    void onlyThatCombination() {
        WingReservations reservations = open();
        reservations.reserve(LILLY, "Lilly", PLUM);

        assertThat(reservations.mayWear(BEN, PLUM.withShape(ParticleShape.DRAGON_WINGS))).isTrue();
        assertThat(reservations.mayWear(BEN, PLUM.withColourTo(0xFFFFFF))).isTrue();
        assertThat(reservations.mayWear(BEN, PLUM.withColour(0x5C203E).withColourTo(0x3B1A2F))).isTrue();
        assertThat(reservations.mayWear(BEN, PLUM.withNatural(true))).as("the style is not the combination")
                .isFalse();
        assertThat(reservations.mayWear(BEN, PLUM.withParticle("ENTITY_EFFECT")))
                .as("a coloured particle is its colours, whichever it is").isFalse();
    }

    @Test
    @DisplayName("wings without a colour are reserved by their particle")
    void uncoloured() {
        WingReservations reservations = open();
        ParticleChoice soulFire = new ParticleChoice("SOUL_FIRE_FLAME", ParticleShape.PHOENIX_WINGS, null);
        reservations.reserve(LILLY, "Lilly", soulFire);

        assertThat(reservations.mayWear(BEN, soulFire)).isFalse();
        assertThat(reservations.mayWear(BEN, soulFire.withParticle("FLAME"))).isTrue();
    }

    @Test
    @DisplayName("one taken is not taken again; one's own twice is said so; nothing worn is nothing to reserve")
    void taken() {
        WingReservations reservations = open();
        reservations.reserve(LILLY, "Lilly", PLUM);

        assertThat(reservations.reserve(BEN, "Ben", PLUM)).isEqualTo(Outcome.TAKEN);
        assertThat(reservations.reserve(LILLY, "Lilly", PLUM)).isEqualTo(Outcome.ALREADY_YOURS);
        assertThat(reservations.reserve(BEN, "Ben", ParticleChoice.NONE)).isEqualTo(Outcome.NOTHING_WORN);
    }

    @Test
    @DisplayName("only its owner lets it go — or staff, who can free anybody's")
    void releasing() {
        WingReservations reservations = open();
        reservations.reserve(LILLY, "Lilly", PLUM);

        assertThat(reservations.release(BEN, false, PLUM)).isEqualTo(Outcome.NOT_YOURS);
        assertThat(reservations.release(BEN, true, PLUM)).isEqualTo(Outcome.RELEASED);
        assertThat(reservations.mayWear(BEN, PLUM)).isTrue();
        assertThat(reservations.release(LILLY, false, PLUM)).isEqualTo(Outcome.NOT_RESERVED);
    }

    @Test
    @DisplayName("kept across a restart")
    void persisted() {
        open().reserve(LILLY, "Lilly", PLUM);

        WingReservations again = open();

        assertThat(again.mayWear(BEN, PLUM)).isFalse();
        assertThat(again.all()).hasSize(1);
        assertThat(again.all().getFirst().shown()).contains("Grand").contains("#3B1A2F").contains("#5C203E");
    }

    @Test
    @DisplayName("staff free everything one player reserved, by their name — without having to put the wings on, which the reservation stops")
    void releasingAllOfSomebody() {
        WingReservations reservations = open();
        reservations.reserve(LILLY, "Lilly", PLUM);
        reservations.reserve(LILLY, "Lilly", PLUM.withShape(ParticleShape.DRAGON_WINGS));
        reservations.reserve(BEN, "Ben", PLUM.withShape(ParticleShape.FAIRY_WINGS));

        assertThat(reservations.releaseAllOf("lilly")).isEqualTo(2);

        assertThat(reservations.mayWear(BEN, PLUM)).isTrue();
        assertThat(open().all()).extracting(WingReservations.Reservation::ownerName).containsExactly("Ben");
        assertThat(reservations.releaseAllOf("nobody")).isZero();
    }

    @Test
    @DisplayName("a colour left over from dust does not make reserved flame wings somebody else's: a particle that takes no colour is its particle")
    void leftOverColour() {
        WingReservations reservations = open();
        ParticleChoice soulFire = new ParticleChoice("SOUL_FIRE_FLAME", ParticleShape.PHOENIX_WINGS, null);
        reservations.reserve(LILLY, "Lilly", soulFire);

        // Wore dust in a colour first, then changed the particle: the colour stays in the choice.
        assertThat(reservations.mayWear(BEN, soulFire.withColour(0x3B1A2F).withColourTo(0x5C203E))).isFalse();
        assertThat(reservations.reserve(BEN, "Ben", soulFire.withColour(0x123456))).isEqualTo(Outcome.TAKEN);
    }
}
