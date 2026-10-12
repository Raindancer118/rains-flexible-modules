package de.raindancer.modules.anticheat.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exemptions a client can set off at will — toggling the elytra, hopping in and out of a boat, a redstone
 * clock running a piston next to them, riptide in the rain — each cover a second or three. Repeated, they
 * covered for ever. Each kind gets a budget per window instead.
 */
class ExemptionBudgetTest {

    private long now = 1_000_000;
    private final PlayerTrack track = new PlayerTrack(UUID.randomUUID(), "Cheater", () -> now);

    private double coveredWhileSpamming(PlayerTrack.Exemption kind, long grant, long every, long over) {
        long covered = 0;
        long start = now;
        while (now - start < over) {
            if ((now - start) % every == 0) {
                track.exempt(kind, grant);
            }
            if (track.exemption() != null) {
                covered += 50;
            }
            now += 50;
        }
        return (double) covered / over;
    }

    @Test
    @DisplayName("toggling the elytra every half second covers well under half the time, not all of it")
    void elytraSpam() {
        assertThat(coveredWhileSpamming(PlayerTrack.Exemption.ELYTRA, 1000, 500, 30_000)).isLessThan(0.45);
    }

    @Test
    @DisplayName("a piston clock beside them does not leave them unchecked")
    void pistonClock() {
        assertThat(coveredWhileSpamming(PlayerTrack.Exemption.PISTON, 1500, 250, 30_000)).isLessThan(0.45);
    }

    @Test
    @DisplayName("one real event is covered in full")
    void oneEvent() {
        track.exempt(PlayerTrack.Exemption.VEHICLE, 1500);
        now += 1400;
        assertThat(track.exemption()).isEqualTo(PlayerTrack.Exemption.VEHICLE);
    }

    @Test
    @DisplayName("what the server does to them — joining, teleporting — is never rationed")
    void serverSide() {
        assertThat(coveredWhileSpamming(PlayerTrack.Exemption.TELEPORTED, 1000, 500, 10_000)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("taking turns between elytra, boat, piston and riptide shares one budget")
    void rotatingKinds() {
        PlayerTrack.Exemption[] kinds = {PlayerTrack.Exemption.ELYTRA, PlayerTrack.Exemption.VEHICLE,
                PlayerTrack.Exemption.PISTON, PlayerTrack.Exemption.RIPTIDE, PlayerTrack.Exemption.BOUNCE};
        long covered = 0;
        long start = now;
        int turn = 0;
        while (now - start < 30_000) {
            if ((now - start) % 500 == 0) {
                track.exempt(kinds[turn++ % kinds.length], 1500);
            }
            if (track.exemption() != null) {
                covered += 50;
            }
            now += 50;
        }
        assertThat((double) covered / 30_000).isLessThan(0.45);
    }

    @Test
    @DisplayName("a firework boost excuses the elytra check only, not every other check")
    void boostIsOnlyForTheElytra() {
        track.exempt(PlayerTrack.Exemption.ELYTRA_BOOST, 3500);
        assertThat(track.isExempt(PlayerTrack.Exemption.ELYTRA_BOOST)).isTrue();
        assertThat(track.exemption()).isNull();
    }
}
