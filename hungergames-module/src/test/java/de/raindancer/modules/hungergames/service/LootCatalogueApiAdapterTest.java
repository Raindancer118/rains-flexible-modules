package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.loot.LootEntry;
import de.raindancer.core.content.loot.LootTables;
import de.raindancer.modules.hungergames.store.LootCatalogue;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LootCatalogueApiAdapter}: real create/duplicate/delete and entry editing over Core's
 * {@link LootTables}, honest about the fields {@link LootEntry} does not model.
 */
class LootCatalogueApiAdapterTest {

    private LootTables tables;
    private LootCatalogueApiAdapter adapter;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        tables = new LootTables(dir.resolve("loot.yml"));
        adapter = new LootCatalogueApiAdapter(new LootCatalogue(tables), tables);
    }

    private LootEndpoints.EntryData entry(String item, boolean custom, int weight) {
        return new LootEndpoints.EntryData(item, custom, weight, 1, 1, true, false, "", List.of(), List.of());
    }

    @Nested
    @DisplayName("tables")
    class Tables {

        @Test
        @DisplayName("a created table can be found, empty, at tier 1")
        void create() {
            assertThat(adapter.createTable("chest")).isEmpty();

            var detail = adapter.table("chest").orElseThrow();
            assertThat(detail.entries()).isEmpty();
        }

        @Test
        @DisplayName("creating over an existing name is refused, not silently replaced")
        void createRefusesADuplicateName() {
            adapter.createTable("chest");

            assertThat(adapter.createTable("chest")).isPresent();
        }

        @Test
        @DisplayName("a duplicated table carries the source's entries, independently")
        void duplicate() {
            adapter.createTable("chest");
            adapter.addEntry("chest", entry("BREAD", false, 10));

            assertThat(adapter.duplicateTable("chest", "barrel")).isEmpty();
            adapter.addEntry("chest", entry("ARROW", false, 5));

            assertThat(adapter.table("barrel").orElseThrow().entries()).hasSize(1);
            assertThat(adapter.table("chest").orElseThrow().entries()).hasSize(2);
        }

        @Test
        @DisplayName("duplicating a table that does not exist is refused")
        void duplicateMissingSource() {
            assertThat(adapter.duplicateTable("nope", "chest")).isPresent();
        }

        @Test
        @DisplayName("a deleted table is actually gone from Core's registry")
        void delete() {
            adapter.createTable("chest");

            assertThat(adapter.deleteTable("chest")).isTrue();
            assertThat(adapter.table("chest")).isEmpty();
        }

        @Test
        @DisplayName("deleting something that was never there says so rather than pretending")
        void deleteMissing() {
            assertThat(adapter.deleteTable("nope")).isFalse();
        }
    }

    @Nested
    @DisplayName("entries")
    class Entries {

        @BeforeEach
        void tableExists() {
            adapter.createTable("chest");
        }

        @Test
        @DisplayName("an added material entry is really in the table Core rolls against")
        void addMaterial() {
            int index = adapter.addEntry("chest", entry("BREAD", false, 10));

            assertThat(index).isZero();
            LootEntry stored = tables.byKey("hungergames:chest").orElseThrow().entries().get(0);
            assertThat(stored.material()).isEqualTo(Material.BREAD);
            assertThat(stored.isCustom()).isFalse();
        }

        @Test
        @DisplayName("an added custom-item entry carries the key, not a material")
        void addCustom() {
            adapter.addEntry("chest", entry("hungergames:medikit", true, 4));

            LootEntry stored = tables.byKey("hungergames:chest").orElseThrow().entries().get(0);
            assertThat(stored.isCustom()).isTrue();
            assertThat(stored.customItem()).contains("hungergames:medikit");
        }

        @Test
        @DisplayName("adding to a table that does not exist is refused, not silently creating one")
        void addToMissingTable() {
            assertThatThrownBy(() -> adapter.addEntry("nope", entry("BREAD", false, 1)))
                    .isInstanceOf(ApiConflictException.class);
        }

        @Test
        @DisplayName("replacing an entry changes exactly that index")
        void replace() {
            adapter.addEntry("chest", entry("BREAD", false, 10));
            adapter.addEntry("chest", entry("ARROW", false, 5));

            assertThat(adapter.replaceEntry("chest", 0, entry("IRON_INGOT", false, 20))).isTrue();

            List<LootEntry> stored = tables.byKey("hungergames:chest").orElseThrow().entries();
            assertThat(stored.get(0).material()).isEqualTo(Material.IRON_INGOT);
            assertThat(stored.get(1).material()).isEqualTo(Material.ARROW);
        }

        @Test
        @DisplayName("replacing an out-of-range index is refused")
        void replaceOutOfRange() {
            assertThat(adapter.replaceEntry("chest", 0, entry("BREAD", false, 1))).isFalse();
        }

        @Test
        @DisplayName("deleting an entry removes exactly that one")
        void delete() {
            adapter.addEntry("chest", entry("BREAD", false, 10));
            adapter.addEntry("chest", entry("ARROW", false, 5));

            assertThat(adapter.deleteEntry("chest", 0)).isTrue();

            List<LootEntry> stored = tables.byKey("hungergames:chest").orElseThrow().entries();
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0).material()).isEqualTo(Material.ARROW);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("an unknown material is refused with a reason, not a crash")
        void unknownMaterial() {
            assertThat(adapter.validateEntry(entry("NOT_A_REAL_MATERIAL", false, 1))).isPresent();
        }

        @Test
        @DisplayName("a real material and a sane range pass")
        void valid() {
            assertThat(adapter.validateEntry(entry("BREAD", false, 10))).isEmpty();
        }

        @Test
        @DisplayName("a custom key is not checked against the material list at all")
        void customIsNotAMaterial() {
            assertThat(adapter.validateEntry(entry("hungergames:medikit", true, 1))).isEmpty();
        }

        @Test
        @DisplayName("a negative weight is refused")
        void negativeWeight() {
            assertThat(adapter.validateEntry(entry("BREAD", false, -1))).isPresent();
        }
    }

    @Nested
    @DisplayName("what this honestly does not model")
    class HonestGaps {

        @Test
        @DisplayName("every returned entry says enabled and no custom text, because LootEntry has none")
        void noExtraFieldsInvented() {
            adapter.createTable("chest");
            adapter.addEntry("chest", entry("BREAD", false, 10));

            var stored = adapter.table("chest").orElseThrow().entries().get(0);
            assertThat(stored.enabled()).isTrue();
            assertThat(stored.unbreakable()).isFalse();
            assertThat(stored.displayName()).isEmpty();
            assertThat(stored.lore()).isEmpty();
            assertThat(stored.enchantments()).isEmpty();
        }

        @Test
        @DisplayName("usage is always reported as zero rather than a guessed number")
        void usageIsHonestlyZero() {
            adapter.createTable("chest");

            assertThat(adapter.table("chest").orElseThrow().usage()).isZero();
            assertThat(adapter.tables().get(0).usage()).isZero();
        }
    }

    @Nested
    @DisplayName("saving and reloading")
    class Persistence {

        @Test
        @DisplayName("save actually writes, and isDirty says so on both sides")
        void save() {
            adapter.createTable("chest");
            assertThat(adapter.isDirty()).isTrue();

            assertThat(adapter.save("test")).isEmpty();
            assertThat(adapter.isDirty()).isFalse();
        }

        @Test
        @DisplayName("reloadFromDisk actually asks Core's registry to read the file again")
        void reload(@TempDir Path dir) {
            Path file = dir.resolve("loot.yml");
            LootTables written = new LootTables(file);
            LootCatalogueApiAdapter writer = new LootCatalogueApiAdapter(new LootCatalogue(written), written);
            writer.createTable("chest");
            writer.save("test");

            LootTables reopened = new LootTables(file);
            LootCatalogueApiAdapter reader = new LootCatalogueApiAdapter(new LootCatalogue(reopened), reopened);

            reader.reloadFromDisk();

            assertThat(reader.table("chest")).isPresent();
        }
    }
}
