package de.raindancer.modules.xaeromap.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** That we only ever speak Xaero's own dialect to somebody whose client actually speaks it. */
class MapClientsTest {

    private final MapClients clients = new MapClients();

    @Test
    @DisplayName("a player is unknown until their client says otherwise")
    void nobodyIsAssumedToHaveTheMod() {
        assertThat(clients.hasAMapMod(UUID.randomUUID()))
                .as("assuming the mod means sending a raw xaero-waypoint line to somebody who has "
                        + "no idea what it is")
                .isFalse();
    }

    @Test
    @DisplayName("a client that registered a map channel is remembered")
    void registeringIsRemembered() {
        UUID player = UUID.randomUUID();

        clients.found(player);

        assertThat(clients.hasAMapMod(player)).isTrue();
        assertThat(clients.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("registering both channels counts once")
    void bothChannelsAreOnePlayer() {
        UUID player = UUID.randomUUID();

        clients.found(player);
        clients.found(player);

        assertThat(clients.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a player who leaves is forgotten")
    void leavingIsForgetting() {
        UUID player = UUID.randomUUID();
        clients.found(player);

        clients.forget(player);

        assertThat(clients.hasAMapMod(player)).isFalse();
        assertThat(clients.count()).isZero();
    }

    @Test
    @DisplayName("nothing at all is a safe thing to be asked about")
    void nullsAreSafe() {
        clients.found(null);

        assertThat(clients.hasAMapMod(null)).isFalse();
        assertThat(clients.count()).isZero();
    }
}
