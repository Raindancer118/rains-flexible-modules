package de.raindancer.modules.moderation.store;

import de.raindancer.modules.moderation.model.MiningLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class XrayLedgersTest {

    private static final UUID WHO = UUID.fromString("e7b725cc-d75e-4eb7-9d2b-5b2652fc435b");

    @Test
    @DisplayName("a find's time survives a restart")
    void timeSurvivesRestart(@TempDir Path folder) {
        XrayLedgers saved = new XrayLedgers(folder, () -> 0L);
        saved.of(WHO, "Miner").dug("world, with comma", 7, 30, -7, MiningLedger.BAIT, 1_760_000_000_000L);
        assertThat(saved.flush()).isTrue();

        XrayLedgers loaded = new XrayLedgers(folder, () -> 0L);
        loaded.load();

        assertThat(loaded.find(WHO).finds()).containsExactly(
                new MiningLedger.Find("world, with comma", 7, 30, -7, MiningLedger.BAIT, 1_760_000_000_000L));
    }

    @Test
    @DisplayName("finds saved before times were kept still load, with no time")
    void oldFindsLoad(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("xray-ledgers.yml"), """
                players:
                  %s:
                    name: Miner
                    updated: 0
                    trail-world: world
                    finds:
                    - 1,-50,2,-2,world
                """.formatted(WHO));

        XrayLedgers loaded = new XrayLedgers(folder, () -> 0L);
        loaded.load();

        assertThat(loaded.find(WHO).finds()).containsExactly(new MiningLedger.Find("world", 1, -50, 2, MiningLedger.BAIT, 0));
    }
}
