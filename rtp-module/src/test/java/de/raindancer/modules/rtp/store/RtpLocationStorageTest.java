package de.raindancer.modules.rtp.store;

import de.raindancer.modules.rtp.model.PreparedSpot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the prepared spots, on disk")
class RtpLocationStorageTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("nothing on disk is an empty pool, not an error")
    void loadingNothingIsEmpty() {
        assertThat(new RtpLocationStorage(folder).load()).isEmpty();
    }

    @Test
    @DisplayName("a spot survives a round trip exactly")
    void roundTrip() {
        RtpLocationStorage storage = new RtpLocationStorage(folder);
        UUID player = UUID.randomUUID();
        PreparedSpot spot = new PreparedSpot("L1", "world", 10, 64, -20,
                Instant.parse("2026-01-01T00:00:00Z"), Set.of(player));

        assertThat(storage.saveAll(List.of(spot))).isTrue();
        List<PreparedSpot> loaded = storage.load();

        assertThat(loaded).hasSize(1);
        PreparedSpot back = loaded.getFirst();
        assertThat(back.id()).isEqualTo("L1");
        assertThat(back.world()).isEqualTo("world");
        assertThat(back.x()).isEqualTo(10);
        assertThat(back.y()).isEqualTo(64);
        assertThat(back.z()).isEqualTo(-20);
        assertThat(back.preparedAt()).isEqualTo(spot.preparedAt());
        assertThat(back.usedBy(player)).isTrue();
    }

    @Test
    @DisplayName("an entry with no world is skipped, not thrown over the whole file")
    void anUnreadableEntryIsSkipped() throws Exception {
        Path file = folder.resolve("rtp-locations.yml");
        Files.writeString(file, """
                version: 1
                locations:
                  L1:
                    x: 0
                    y: 64
                    z: 0
                    prepared-at: "2026-01-01T00:00:00Z"
                  L2:
                    world: world
                    x: 5
                    y: 70
                    z: 5
                    prepared-at: "2026-01-01T00:00:00Z"
                """);

        List<PreparedSpot> loaded = new RtpLocationStorage(folder).load();

        assertThat(loaded).extracting(PreparedSpot::id).containsExactly("L2");
    }

    @Test
    @DisplayName("a mangled player in used-by is skipped, not the whole spot")
    void aMangledUsedByEntryIsSkipped() throws Exception {
        Path file = folder.resolve("rtp-locations.yml");
        Files.writeString(file, """
                version: 1
                locations:
                  L1:
                    world: world
                    x: 0
                    y: 64
                    z: 0
                    prepared-at: "2026-01-01T00:00:00Z"
                    used-by:
                      - not-a-uuid
                """);

        List<PreparedSpot> loaded = new RtpLocationStorage(folder).load();

        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().usedBy()).isEmpty();
    }
}
