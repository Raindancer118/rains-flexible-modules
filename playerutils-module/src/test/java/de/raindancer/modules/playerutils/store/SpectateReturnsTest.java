package de.raindancer.modules.playerutils.store;

import org.bukkit.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpectateReturnsTest {

    private final UUID watched = UUID.randomUUID();

    @Test
    @DisplayName("the way back survives being written down, with the server it leads to")
    void roundTrip() {
        var back = new SpectateReturns.Return("world", 1.5, 64, -2, 90f, 10f, GameMode.CREATIVE, watched, "smp");

        assertThat(SpectateReturns.Return.decode(back.encode())).contains(back);
        assertThat(back.isElsewhere("arena")).isTrue();
        assertThat(back.isElsewhere("smp")).isFalse();
    }

    @Test
    @DisplayName("a way back written before servers existed leads to this server")
    void oldFormat() {
        String old = String.join(";", "world", "1.0", "64.0", "2.0", "0.0", "0.0", "SURVIVAL", watched.toString());

        var back = SpectateReturns.Return.decode(old).orElseThrow();

        assertThat(back.server()).isEmpty();
        assertThat(back.isElsewhere("smp")).isFalse();
        assertThat(back).isEqualTo(new SpectateReturns.Return("world", 1, 64, 2, 0f, 0f, GameMode.SURVIVAL, watched));
    }

    @Test
    @DisplayName("garbage is no way back")
    void garbage() {
        assertThat(SpectateReturns.Return.decode(null)).isEmpty();
        assertThat(SpectateReturns.Return.decode("a;b")).isEmpty();
        assertThat(SpectateReturns.Return.decode("w;x;1;1;1;1;SURVIVAL;" + watched)).isEmpty();
    }
}
