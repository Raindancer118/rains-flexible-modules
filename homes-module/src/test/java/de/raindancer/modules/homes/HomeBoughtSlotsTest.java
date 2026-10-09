package de.raindancer.modules.homes;

import de.raindancer.modules.homes.store.BoughtSlots;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HomeBoughtSlotsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("bought slots are counted per player and survive a restart")
    void persistent() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        BoughtSlots slots = new BoughtSlots(dir.resolve("bought-slots.yml"));
        slots.load();
        assertThat(slots.of(a)).isZero();

        assertThat(slots.add(a)).isTrue();
        assertThat(slots.add(a)).isTrue();
        assertThat(slots.add(b)).isTrue();

        BoughtSlots reread = new BoughtSlots(dir.resolve("bought-slots.yml"));
        reread.load();
        assertThat(reread.of(a)).isEqualTo(2);
        assertThat(reread.of(b)).isEqualTo(1);
    }

    @Test
    @DisplayName("a write that fails is not counted")
    void failedWriteIsRolledBack() throws Exception {
        Path notAFile = dir.resolve("a-folder");
        java.nio.file.Files.createDirectories(notAFile.resolve("inside"));
        UUID a = UUID.randomUUID();
        BoughtSlots slots = new BoughtSlots(notAFile);

        assertThat(slots.add(a)).isFalse();
        assertThat(slots.of(a)).isZero();
    }
}
