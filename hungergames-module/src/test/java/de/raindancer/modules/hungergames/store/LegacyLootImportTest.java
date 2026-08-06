package de.raindancer.modules.hungergames.store;

import de.raindancer.core.content.loot.LootEntry;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LegacyLootImport}: reading an old server's own {@code loot.yml}, so an upgrade keeps a
 * gamemaster's own tuning rather than being overwritten by {@link LootDefaults}' shipped pools.
 */
@DisplayName("LegacyLootImport reads an old loot.yml without ever throwing on a bad one")
class LegacyLootImportTest {

    @Test
    @DisplayName("a real fragment of the live file round-trips to the right table, entries, weights and amounts")
    void aRealFragmentRoundTrips(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                loot:
                  fill-percentage:
                    chest: 40
                  chest:
                  - item: WOODEN_SWORD
                    weight: 25
                    amount: 1
                  - item: BREAD
                    weight: 30
                    amount: 2-4
                  - item: STONE
                    weight: 20
                    amount: 1-101
                """);

        LegacyLootImport.Imported imported = LegacyLootImport.from(file);

        assertThat(imported.report().problems()).isEmpty();
        assertThat(imported.tables()).containsOnlyKeys("chest");

        LootDefaults.Table chest = imported.tables().get("chest");
        assertThat(chest.fillPercent()).isEqualTo(40);
        assertThat(chest.entries()).hasSize(3);

        LootEntry sword = entryFor(chest, Material.WOODEN_SWORD);
        assertThat(sword.weight()).isEqualTo(25);
        assertThat(sword.minimum()).isEqualTo(1);
        assertThat(sword.maximum()).isEqualTo(1);

        LootEntry bread = entryFor(chest, Material.BREAD);
        assertThat(bread.weight()).isEqualTo(30);
        assertThat(bread.minimum()).isEqualTo(2);
        assertThat(bread.maximum()).isEqualTo(4);

        LootEntry stone = entryFor(chest, Material.STONE);
        assertThat(stone.minimum()).isEqualTo(1);
        assertThat(stone.maximum()).isEqualTo(101);
    }

    @Test
    @DisplayName("a malformed file is reported, never thrown, and nothing is imported from it")
    void malformedFileIsReportedNotThrown(@TempDir Path dir) throws IOException {
        Path file = write(dir, "loot: [broken: [[[");

        LegacyLootImport.Imported imported = LegacyLootImport.from(file);

        assertThat(imported.report().problems()).isNotEmpty();
        assertThat(imported.tables()).isEmpty();
    }

    @Test
    @DisplayName("a file that does not exist is reported rather than throwing an unchecked exception")
    void unreadableFileIsReportedNotThrown(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.yml");

        LegacyLootImport.Imported imported = LegacyLootImport.from(missing);

        assertThat(imported.report().problems()).isNotEmpty();
        assertThat(imported.tables()).isEmpty();
    }

    @Test
    @DisplayName("a plain number amount becomes exactly that many, and a range becomes a range")
    void amountsAreParsedTheOldPluginsWay(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                loot:
                  barrel:
                  - item: ARROW
                    weight: 30
                    amount: 8-16
                  - item: TORCH
                    weight: 15
                    amount: 5
                """);

        LegacyLootImport.Imported imported = LegacyLootImport.from(file);

        LootDefaults.Table barrel = imported.tables().get("barrel");
        LootEntry arrows = entryFor(barrel, Material.ARROW);
        assertThat(arrows.minimum()).isEqualTo(8);
        assertThat(arrows.maximum()).isEqualTo(16);

        LootEntry torches = entryFor(barrel, Material.TORCH);
        assertThat(torches.minimum()).isEqualTo(5);
        assertThat(torches.maximum()).isEqualTo(5);
    }

    @Test
    @DisplayName("an amount neither parser can read defaults to exactly one and is reported")
    void unparseableAmountDefaultsToOneAndIsReported(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                loot:
                  chest:
                  - item: STICK
                    weight: 10
                    amount: a-lot
                """);

        LegacyLootImport.Imported imported = LegacyLootImport.from(file);

        LootEntry stick = entryFor(imported.tables().get("chest"), Material.STICK);
        assertThat(stick.minimum()).isEqualTo(1);
        assertThat(stick.maximum()).isEqualTo(1);
        assertThat(imported.report().problems())
                .anySatisfy(problem -> assertThat(problem).contains("STICK").contains("a-lot"));
    }

    @Test
    @DisplayName("an unknown material is reported and its entry is skipped, not guessed at")
    void unknownMaterialIsReportedAndSkipped(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                loot:
                  chest:
                  - item: WOODEN_SWORD
                    weight: 25
                    amount: 1
                  - item: NOT_A_REAL_MATERIAL
                    weight: 10
                    amount: 1
                """);

        LegacyLootImport.Imported imported = LegacyLootImport.from(file);

        LootDefaults.Table chest = imported.tables().get("chest");
        assertThat(chest.entries()).hasSize(1);
        assertThat(chest.entries().get(0).material()).isEqualTo(Material.WOODEN_SWORD);
        assertThat(imported.report().problems())
                .anySatisfy(problem -> assertThat(problem).contains("NOT_A_REAL_MATERIAL"));
    }

    @Test
    @DisplayName("a custom entry becomes the key the registry actually knows, not the old file's spelling")
    void customEntryBecomesARegistryKey(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                loot:
                  copper-chest:
                  - item: KRUECKAUWASSER
                    custom: true
                    weight: 10
                    amount: 1-10
                """);

        LegacyLootImport.Imported imported = LegacyLootImport.from(file);

        LootDefaults.Table copperChest = imported.tables().get("copper-chest");
        assertThat(copperChest.entries()).hasSize(1);
        LootEntry custom = copperChest.entries().get(0);
        assertThat(custom.isCustom()).isTrue();
        // This line used to read "KRUECKAUWASSER" — the file's own spelling, asserted as though keeping it
        // were the point. Core's registry knows the item as hungergames:krueckauwasser, so what the old
        // assertion actually locked in was an entry naming an item that does not exist. A loot entry for a
        // missing item yields nothing, so every custom entry in a real server's file was silently dropped and
        // the chests came out looking full. Reported as "the custom items are still not in the chests".
        assertThat(custom.customKey()).isEqualTo("hungergames:krueckauwasser");
        assertThat(custom.minimum()).isEqualTo(1);
        assertThat(custom.maximum()).isEqualTo(10);
    }

    @Test
    @DisplayName("an id that already names its plugin is left alone")
    void anAlreadyNamespacedIdIsNotRehomed(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                loot:
                  copper-chest:
                  - item: someotherplugin:relic
                    custom: true
                    weight: 3
                """);

        LootEntry custom = LegacyLootImport.from(file).tables().get("copper-chest").entries().get(0);

        // A deliberate cross-plugin reference. Prefixing it would break the one kind of entry that was
        // written correctly in order to tidy the ones that were not.
        assertThat(custom.customKey()).isEqualTo("someotherplugin:relic");
    }

    @Test
    @DisplayName("every custom item the live server's loot.yml names resolves to a registered key")
    void theLiveFilesCustomItemsAllResolve(@TempDir Path dir) throws IOException {
        // The seven ids that are actually in the checked-in live file, in the file's own spelling.
        for (String written : List.of("KRUECKAUWASSER", "SMOKE_BOMB", "FIENDFINDER",
                "STUPIDNESS_PROTECTOR", "LEAP", "REPULSE", "MEDIKIT")) {
            Path file = write(dir, """
                    loot:
                      copper-chest:
                      - item: %s
                        custom: true
                        weight: 1
                    """.formatted(written));

            LootEntry custom = LegacyLootImport.from(file).tables().get("copper-chest").entries().get(0);

            assertThat(custom.customKey())
                    .as("%s has to become a key the item registry knows, or it is loot that never appears",
                            written)
                    .startsWith("hungergames:")
                    .doesNotContain("_")
                    .isLowerCase();
        }
    }

    @Test
    @DisplayName("report().lines() ends with the problems, worst last, like the settings importer's report")
    void reportLinesEndsWithProblems(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                loot:
                  chest:
                  - item: NOT_A_REAL_MATERIAL
                    weight: 10
                    amount: 1
                """);

        LegacyLootImport.Imported imported = LegacyLootImport.from(file);
        List<String> lines = imported.report().lines();

        assertThat(lines).isNotEmpty();
        assertThat(lines.get(lines.size() - 1)).contains("NOT_A_REAL_MATERIAL");
    }

    private static LootEntry entryFor(LootDefaults.Table table, Material material) {
        Optional<LootEntry> found = table.entries().stream()
                .filter(entry -> entry.material() == material)
                .findFirst();
        assertThat(found).as("an entry for %s in table '%s'", material, table.name()).isPresent();
        return found.get();
    }

    private static Path write(Path dir, String content) throws IOException {
        Path file = dir.resolve("loot.yml");
        Files.writeString(file, content);
        return file;
    }
}
