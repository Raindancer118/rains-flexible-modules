package de.raindancer.modules.hungergames.store;

import de.raindancer.core.content.loot.LootTable;
import de.raindancer.core.content.loot.LootTables;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The module's door to its loot tables, which are RainsCore's.
 *
 * <h2>Why there is no store of its own</h2>
 * Because a loot table is a pool of weighted entries with a fill percentage, and RainsCore already keeps
 * exactly that shape in {@code loot.yml} — tiers, weighted rolling and the entry-lost-not-table resilience
 * a renamed block needs are all {@link LootTables}'. A second model here, the way the source plugin's
 * {@code LootConfig} and its own {@code LootTableRepository} were, is a second answer to "what is in the
 * chest": one drifts from the other the first time a table gains a field neither copy of the model knows
 * about, and the port that keeps both is the port that reinvented what {@code ReuseTest} exists to catch.
 *
 * <p>So what is here plays the same part for loot tables that the farm-world module's own
 * {@code FarmWorldCatalogue} plays for farm worlds: the module's own view over Core's registry, scoped to
 * the tables this plugin defined and named the way this plugin names them — {@code chest} rather than
 * {@code hungergames:chest} — so that a service asking for the supply-drop table does not have to know
 * Core's key format at all.
 */
public final class LootCatalogue {

    /** The plugin id every one of this module's tables is defined under, in Core's {@code plugin:id} keys. */
    public static final String PLUGIN = "hungergames";

    private final LootTables tables;

    public LootCatalogue(LootTables tables) {
        this.tables = tables;
    }

    /** Every table this module has defined, by its short name (without the {@code hungergames:} prefix). */
    public List<LootTable> all() {
        return tables.ofPlugin(PLUGIN).stream()
                .sorted(Comparator.comparing(LootTable::id))
                .toList();
    }

    /** A table by its short name, e.g. {@code "chest"} rather than {@code "hungergames:chest"}. */
    public Optional<LootTable> byName(String name) {
        return name == null ? Optional.empty() : tables.byKey(PLUGIN + ":" + name);
    }

    /** Whether a short name is already taken by one of this module's tables. */
    public boolean exists(String name) {
        return byName(name).isPresent();
    }

    /** The short names, for tab completion and the editor's list. */
    public List<String> names() {
        return all().stream().map(LootTable::id).toList();
    }

    /**
     * Defines a table, replacing any of the same name — how the module ships its bundled tables (the
     * arena's chests, barrels and shelves, and the supply drop) without a second copy of {@link LootTables}'
     * own file format.
     */
    public void define(String name, int tier, int fillPercent, List<de.raindancer.core.content.loot.LootEntry> entries) {
        tables.define(build(name, tier, fillPercent, entries));
    }

    /** Ships a default table only if nobody has defined one under that name yet — an owner's edits stand. */
    public boolean defineIfAbsent(String name, int tier, int fillPercent,
                                   List<de.raindancer.core.content.loot.LootEntry> entries) {
        return tables.defineIfAbsent(build(name, tier, fillPercent, entries));
    }

    private static LootTable build(String name, int tier, int fillPercent,
                                    List<de.raindancer.core.content.loot.LootEntry> entries) {
        LootTable.Builder builder = LootTable.builder(PLUGIN, name).tier(tier).fillPercent(fillPercent);
        entries.forEach(builder::entry);
        return builder.build();
    }

    /** What Core's registry could not read from {@code loot.yml} the last time it loaded — every plugin's, not
     * only this one's, because a table this module rolls against might have been broken by another plugin's
     * hand-edit and the owner needs to see that too. */
    public List<String> problems() {
        return tables.problems();
    }
}
