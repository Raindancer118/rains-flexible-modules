package de.raindancer.modules.moderation.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BaitSeedTest {

    @Test
    @DisplayName("the seed is made once and kept, so the bait layer stays put across restarts")
    void kept(@TempDir Path folder) {
        long first = BaitSeed.load(folder);
        assertThat(BaitSeed.load(folder)).isEqualTo(first);
        assertThat(BaitSeed.load(folder.resolve("other"))).isNotEqualTo(first);
    }

    @Test
    @DisplayName("an unreadable seed file is replaced, not a crash")
    void unreadable(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve(BaitSeed.FILE), "not a number");
        long seed = BaitSeed.load(folder);
        assertThat(BaitSeed.load(folder)).isEqualTo(seed);
    }
}
