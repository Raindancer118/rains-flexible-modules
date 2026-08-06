package de.raindancer.modules.hungergames;

import de.raindancer.core.content.loot.LootEntry;
import de.raindancer.core.content.loot.LootTable;
import de.raindancer.core.content.loot.LootTables;
import de.raindancer.modules.hungergames.store.LootCatalogue;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LootCatalogue}: a thin door onto Core's {@link LootTables}, scoped to this module's own tables and
 * named the way this module names them.
 */
class LootCatalogueTest {

    @Test
    @DisplayName("a defined table is found by its short name, and Core's plugin:id key stays hidden")
    void definesAndFindsByShortName(@TempDir Path dir) {
        LootTables tables = new LootTables(dir.resolve("loot.yml"));
        LootCatalogue catalogue = new LootCatalogue(tables);

        catalogue.define("chest", 1, 30, List.of(LootEntry.of(Material.BREAD, 10)));

        assertThat(catalogue.exists("chest")).isTrue();
        assertThat(catalogue.byName("chest")).isPresent();
        assertThat(catalogue.byName("chest").orElseThrow().key()).isEqualTo("hungergames:chest");
        assertThat(catalogue.names()).containsExactly("chest");
    }

    @Test
    @DisplayName("only this module's own tables are listed, not another plugin's")
    void scopedToThisPlugin() {
        LootTables tables = new LootTables(Path.of("unused-in-this-test.yml"));
        tables.define(LootTable.builder("hungergames", "chest").entry(LootEntry.of(Material.BREAD, 1)).build());
        tables.define(LootTable.builder("other-plugin", "chest").entry(LootEntry.of(Material.STONE, 1)).build());
        LootCatalogue catalogue = new LootCatalogue(tables);

        assertThat(catalogue.all()).hasSize(1);
        assertThat(catalogue.all().get(0).key()).isEqualTo("hungergames:chest");
    }

    @Test
    @DisplayName("defineIfAbsent keeps an owner's existing table rather than overwriting it")
    void defineIfAbsentKeepsExisting() {
        LootTables tables = new LootTables(Path.of("unused-in-this-test.yml"));
        LootCatalogue catalogue = new LootCatalogue(tables);
        catalogue.define("chest", 1, 50, List.of(LootEntry.of(Material.BREAD, 5)));

        boolean added = catalogue.defineIfAbsent("chest", 1, 99, List.of(LootEntry.of(Material.DIAMOND, 1)));

        assertThat(added).isFalse();
        assertThat(catalogue.byName("chest").orElseThrow().fillPercent()).isEqualTo(50);
    }

    @Test
    @DisplayName("an unknown short name is empty, not an exception")
    void unknownName() {
        LootTables tables = new LootTables(Path.of("unused-in-this-test.yml"));
        LootCatalogue catalogue = new LootCatalogue(tables);

        assertThat(catalogue.byName("no-such-table")).isEmpty();
        assertThat(catalogue.exists("no-such-table")).isFalse();
    }

    @Test
    @DisplayName("problems surface Core's own read failures, not a second copy of them")
    void surfacesCoreProblems(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("loot.yml");
        Files.writeString(file, "tables: [broken: [[[");
        LootTables tables = new LootTables(file);
        tables.load();
        LootCatalogue catalogue = new LootCatalogue(tables);

        assertThat(catalogue.problems()).isNotEmpty();
    }
}
