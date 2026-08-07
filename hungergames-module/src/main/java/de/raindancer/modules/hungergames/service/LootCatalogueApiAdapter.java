package de.raindancer.modules.hungergames.service;

import de.raindancer.core.content.loot.LootEntry;
import de.raindancer.core.content.loot.LootTable;
import de.raindancer.core.content.loot.LootTables;
import de.raindancer.modules.hungergames.store.LootCatalogue;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link LootEndpoints.Catalogue}, over the module's {@link LootCatalogue} and, for the two operations it
 * does not itself expose — deleting a table and writing to disk — Core's {@link LootTables} directly.
 *
 * <h2>What this can and cannot do, honestly</h2>
 * Creating, duplicating, deleting a table and adding, replacing or deleting an entry are all real:
 * {@link LootCatalogue#define} already does exactly "replace whatever is at this name", which is every one
 * of those in one call once the entry list is built here first.
 *
 * <p>What is <b>not</b> real, because {@link LootEntry} does not carry it: {@code enabled}, a
 * per-entry on/off switch; {@code unbreakable}; a custom {@code displayName}, {@code lore} or
 * {@code enchantments}. An entry here is a material or a custom item key, a weight, and an amount range —
 * that is the whole of what {@link LootEntry} models, ported from the source's own loot config. Every
 * {@link LootEndpoints.EntryData} this returns carries {@code enabled=true}, {@code unbreakable=false} and
 * empty text for the rest, and every one it is given ignores those fields rather than pretending to store
 * them. Extending {@link LootEntry} itself to carry them is a Core change, not a wiring one.
 *
 * <p>{@link LootEndpoints.TableSummary#usage()} and {@link LootEndpoints.TableDetail#usage()} are always
 * {@code 0}: nothing in this module keeps a cross-reference of which supply drop, container fill or
 * sponsor entry names which table, so "how many places use this" is not a question anything here can
 * currently answer without guessing.
 */
public final class LootCatalogueApiAdapter implements LootEndpoints.Catalogue {

    private final LootCatalogue catalogue;
    private final LootTables tables;

    public LootCatalogueApiAdapter(LootCatalogue catalogue, LootTables tables) {
        this.catalogue = catalogue;
        this.tables = tables;
    }

    @Override
    public List<LootEndpoints.TableSummary> tables() {
        return catalogue.all().stream()
                .map(table -> new LootEndpoints.TableSummary(table.id(), table.entries().size(),
                        totalWeight(table), 0))
                .toList();
    }

    @Override
    public boolean isDirty() {
        return tables.isDirty();
    }

    @Override
    public Optional<LootEndpoints.TableDetail> table(String name) {
        return catalogue.byName(name).map(table -> new LootEndpoints.TableDetail(table.id(), 0,
                totalWeight(table), table.entries().stream().map(LootCatalogueApiAdapter::toEntryData).toList()));
    }

    @Override
    public Optional<String> createTable(String name) {
        String problem = badName(name);
        if (problem != null) {
            return Optional.of(problem);
        }
        if (catalogue.exists(name)) {
            return Optional.of("a table called \"" + name + "\" already exists");
        }
        catalogue.define(name, 1, 100, List.of());
        return Optional.empty();
    }

    @Override
    public Optional<String> duplicateTable(String source, String name) {
        Optional<LootTable> original = catalogue.byName(source);
        if (original.isEmpty()) {
            return Optional.of("no table called \"" + source + "\"");
        }
        String problem = badName(name);
        if (problem != null) {
            return Optional.of(problem);
        }
        if (catalogue.exists(name)) {
            return Optional.of("a table called \"" + name + "\" already exists");
        }
        LootTable copy = original.get();
        catalogue.define(name, copy.tier(), copy.fillPercent(), copy.entries());
        return Optional.empty();
    }

    @Override
    public boolean deleteTable(String name) {
        return name != null && tables.undefine(LootCatalogue.PLUGIN + ":" + name.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public List<String> validateAll() {
        return tables.problems();
    }

    @Override
    public List<String> save(String actor) {
        tables.flush();
        return tables.isDirty()
                ? List.of("the write did not go through — check the server's disk and file permissions")
                : List.of();
    }

    @Override
    public void reloadFromDisk() {
        tables.load();
    }

    @Override
    public Optional<String> validateEntry(LootEndpoints.EntryData entry) {
        if (entry.item() == null || entry.item().isBlank()) {
            return Optional.of("no item was given");
        }
        if (!entry.custom() && Material.matchMaterial(entry.item()) == null) {
            return Optional.of("\"" + entry.item() + "\" is not a material — tick \"custom\" for a "
                    + "custom item, or check the spelling");
        }
        if (entry.weight() < 0) {
            return Optional.of("weight must not be negative");
        }
        if (entry.minAmount() < 1 || entry.maxAmount() < entry.minAmount()) {
            return Optional.of("the amount range is invalid — minimum must be at least 1 and no greater "
                    + "than maximum");
        }
        return Optional.empty();
    }

    @Override
    public int addEntry(String table, LootEndpoints.EntryData entry) {
        LootTable current = catalogue.byName(table).orElseThrow(() -> noSuchTable(table));
        List<LootEntry> entries = new ArrayList<>(current.entries());
        entries.add(toLootEntry(entry));
        catalogue.define(table, current.tier(), current.fillPercent(), entries);
        return entries.size() - 1;
    }

    @Override
    public boolean replaceEntry(String table, int index, LootEndpoints.EntryData entry) {
        Optional<LootTable> current = catalogue.byName(table);
        if (current.isEmpty() || index < 0 || index >= current.get().entries().size()) {
            return false;
        }
        List<LootEntry> entries = new ArrayList<>(current.get().entries());
        entries.set(index, toLootEntry(entry));
        catalogue.define(table, current.get().tier(), current.get().fillPercent(), entries);
        return true;
    }

    @Override
    public boolean deleteEntry(String table, int index) {
        Optional<LootTable> current = catalogue.byName(table);
        if (current.isEmpty() || index < 0 || index >= current.get().entries().size()) {
            return false;
        }
        List<LootEntry> entries = new ArrayList<>(current.get().entries());
        entries.remove(index);
        catalogue.define(table, current.get().tier(), current.get().fillPercent(), entries);
        return true;
    }

    private static String badName(String name) {
        return name == null || name.isBlank() ? "no name was given" : null;
    }

    private static ApiConflictException noSuchTable(String name) {
        return new ApiConflictException("no table called \"" + name + "\"");
    }

    private static int totalWeight(LootTable table) {
        return table.entries().stream().mapToInt(LootEntry::weight).sum();
    }

    private static LootEndpoints.EntryData toEntryData(LootEntry entry) {
        return new LootEndpoints.EntryData(entry.describe(), entry.isCustom(), entry.weight(),
                entry.minimum(), entry.maximum(), true, false, "", List.of(), List.of());
    }

    private static LootEntry toLootEntry(LootEndpoints.EntryData entry) {
        LootEntry built = entry.custom()
                ? LootEntry.ofCustomItem(entry.item(), entry.weight())
                : LootEntry.of(Material.matchMaterial(entry.item()), entry.weight());
        return built.amount(entry.minAmount(), entry.maxAmount());
    }
}
