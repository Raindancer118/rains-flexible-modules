package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.service.TrackerCompass.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Where a Runner was last seen leaving each world. Bukkit-free, like everything it is asked by. */
class PortalMemoryTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());

    @Test
    @DisplayName("nothing is remembered about a Runner who has never crossed")
    void emptyToStart() {
        assertThat(new PortalMemory().lastCrossingIn(ANNA, "hunt")).isEmpty();
    }

    @Test
    @DisplayName("a crossing is remembered against the world it was made in")
    void remembersACrossing() {
        PortalMemory memory = new PortalMemory();
        memory.remember(ANNA, new Point("hunt", 10, 64, 20));

        assertThat(memory.lastCrossingIn(ANNA, "hunt")).contains(new Point("hunt", 10, 64, 20));
        assertThat(memory.lastCrossingIn(ANNA, "hunt_nether")).isEmpty();
    }

    @Test
    @DisplayName("crossing the same world twice keeps only the newest")
    void newestWins() {
        PortalMemory memory = new PortalMemory();
        memory.remember(ANNA, new Point("hunt", 10, 64, 20));
        memory.remember(ANNA, new Point("hunt", 900, 64, 20));

        assertThat(memory.lastCrossingIn(ANNA, "hunt")).contains(new Point("hunt", 900, 64, 20));
    }

    @Test
    @DisplayName("one world's crossing does not overwrite another's")
    void worldsAreSeparate() {
        PortalMemory memory = new PortalMemory();
        memory.remember(ANNA, new Point("hunt", 10, 64, 20));
        memory.remember(ANNA, new Point("hunt_nether", 1, 64, 2));

        assertThat(memory.lastCrossingIn(ANNA, "hunt")).contains(new Point("hunt", 10, 64, 20));
        assertThat(memory.lastCrossingIn(ANNA, "hunt_nether")).contains(new Point("hunt_nether", 1, 64, 2));
    }

    @Test
    @DisplayName("one Runner's crossings are not another's")
    void runnersAreSeparate() {
        PortalMemory memory = new PortalMemory();
        memory.remember(ANNA, new Point("hunt", 10, 64, 20));

        assertThat(memory.lastCrossingIn(BEN, "hunt")).isEmpty();
    }

    @Test
    @DisplayName("clearing forgets every crossing, so a new hunt starts blind")
    void clearing() {
        PortalMemory memory = new PortalMemory();
        memory.remember(ANNA, new Point("hunt", 10, 64, 20));
        memory.remember(BEN, new Point("hunt", 30, 64, 40));
        memory.clear();

        assertThat(memory.lastCrossingIn(ANNA, "hunt")).isEmpty();
        assertThat(memory.lastCrossingIn(BEN, "hunt")).isEmpty();
    }

    @Test
    @DisplayName("a null crossing is ignored rather than stored")
    void nullsAreIgnored() {
        PortalMemory memory = new PortalMemory();
        memory.remember(ANNA, null);
        memory.remember(null, new Point("hunt", 1, 1, 1));

        assertThat(memory.lastCrossingIn(ANNA, "hunt")).isEmpty();
    }
}
