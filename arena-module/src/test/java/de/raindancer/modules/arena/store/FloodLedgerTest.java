package de.raindancer.modules.arena.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FloodLedgerTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("what a flood changed is on disk before the water is, and read back whole after a crash")
    void roundTrip() {
        FloodLedger ledger = new FloodLedger(folder.resolve("floods"));
        List<FloodLedger.Change> changes = List.of(
                new FloodLedger.Change("arena", -3, 67, 12, "minecraft:air"),
                new FloodLedger.Change("arena", 4, 67, -9, "minecraft:short_grass"),
                new FloodLedger.Change("arena", 0, 66, 0, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"));
        assertThat(ledger.write("colosseum", changes)).isTrue();

        FloodLedger after = new FloodLedger(folder.resolve("floods"));
        assertThat(after.pending()).containsExactly("colosseum");
        assertThat(after.read("colosseum")).containsExactlyElementsOf(changes);

        after.done("colosseum");
        assertThat(after.pending()).isEmpty();
    }

    @Test
    @DisplayName("a line that cannot be read is skipped, the rest still restored")
    void damaged() throws Exception {
        FloodLedger ledger = new FloodLedger(folder.resolve("floods"));
        ledger.write("pit2", List.of(new FloodLedger.Change("arena_2", 1, 2, 3, "minecraft:air")));
        new de.raindancer.core.data.store.YamlStore(folder.resolve("floods").resolve("pit2.yml")).update(yaml -> {
            List<String> lines = new java.util.ArrayList<>(yaml.getStringList("changes"));
            lines.add("garbage line");
            yaml.set("changes", lines);
        });
        assertThat(ledger.read("pit2")).hasSize(1);
    }
}
