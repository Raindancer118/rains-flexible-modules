package de.raindancer.modules.xaeromap.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the packet that gives each world its own map is the packet both Xaero mods read.
 *
 * <p>Five bytes, and every one of them matters: the mods' own deserialiser refuses anything whose
 * first byte is not zero, and reads the id big-endian. Written the other way round, every world still
 * gets an id — a different one from the one this server thinks it sent — which is a map cache that
 * changes identity whenever the byte order of the number happens to differ.
 */
class XaeroWorldIdTest {

    @Test
    @DisplayName("the packet is the marker byte and a big-endian int")
    void thepacketIsFiveBytes() {
        byte[] packet = XaeroWorldId.packet(0x01020304);

        assertThat(packet).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    @DisplayName("a negative id survives the trip, because a hash is as often negative as not")
    void negativeIdsAreFine() {
        byte[] packet = XaeroWorldId.packet(-1);

        assertThat(packet).containsExactly(0, -1, -1, -1, -1);
    }

    @Test
    @DisplayName("a world's id is its own and does not move")
    void idsAreStableAndDistinct() {
        UUID one = UUID.randomUUID();

        assertThat(XaeroWorldId.of(one))
                .as("a world whose id changes between restarts is a map cache thrown away every "
                        + "restart")
                .isEqualTo(XaeroWorldId.of(one));

        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            ids.add(XaeroWorldId.of(UUID.randomUUID()));
        }
        assertThat(ids)
                .as("two worlds sharing an id is the very bug this fixes — one map drawn over "
                        + "the other")
                .hasSize(200);
    }

    @Test
    @DisplayName("both mods are told, because a player may have either")
    void thereAreTwoChannels() {
        assertThat(XaeroWorldId.MINIMAP_CHANNEL).isEqualTo("xaerominimap:main");
        assertThat(XaeroWorldId.WORLDMAP_CHANNEL).isEqualTo("xaeroworldmap:main");
    }

    @Test
    @DisplayName("a world with no uuid at all still produces a packet rather than throwing")
    void nothingIsStillSomething() {
        assertThat(XaeroWorldId.of(null)).isZero();
    }
}
