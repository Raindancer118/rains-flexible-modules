package de.raindancer.modules.hungergames.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.raindancer.modules.hungergames.HungerGamesSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Loot table endpoints: create, duplicate, delete tables, edit their entries, validate, and write to
 * disk.
 *
 * <h2>Why this depends on a port</h2>
 * {@code store/LootCatalogue} — a thin door onto Core's {@code content.loot.LootTables}, the way
 * farmworld's {@code FarmWorldCatalogue} is a door onto Core's {@code FarmWorlds} — lives in
 * {@code store/}, which is off limits to this port: other hands are building it, concurrently, in the
 * same wave. {@link Catalogue} is the seam between this transport and that store, stated as exactly what
 * the HTTP layer needs; wiring the real catalogue in is one line wherever this module assembles its
 * services.
 */
final class LootEndpoints implements ApiRouter.Module, IHungerGamesService {

    /** One table, for the list view. */
    record TableSummary(String name, int entries, int totalWeight, int usage) {
    }

    /** One entry, in or out. */
    record EntryData(String item, boolean custom, int weight, int minAmount, int maxAmount,
                      boolean enabled, boolean unbreakable, String displayName, List<String> lore,
                      List<String> enchantments) {
    }

    /** A table with every entry, for the detail view. */
    record TableDetail(String name, int usage, int totalWeight, List<EntryData> entries) {
    }

    interface Catalogue {
        List<TableSummary> tables();

        boolean isDirty();

        Optional<TableDetail> table(String name);

        /** @return an error message, or empty on success */
        Optional<String> createTable(String name);

        Optional<String> duplicateTable(String source, String name);

        boolean deleteTable(String name);

        /** @return every problem found; empty means every table is valid */
        List<String> validateAll();

        /** @return errors that stopped the save; empty means it succeeded */
        List<String> save(String actor);

        void reloadFromDisk();

        /** @return a validation problem, or empty when the entry is acceptable */
        Optional<String> validateEntry(EntryData entry);

        /** @return the index the entry was added at */
        int addEntry(String table, EntryData entry);

        boolean replaceEntry(String table, int index, EntryData entry);

        boolean deleteEntry(String table, int index);
    }

    private final ApiSupport support;
    private final Catalogue catalogue;

    LootEndpoints(ApiSupport support, Catalogue catalogue) {
        this.support = support;
        this.catalogue = catalogue;
    }

    @Override
    public void register(ApiRouter router) {
        router.get("/api/loot/tables", "Loot tables with their entry count and weight", this::tables);
        router.post("/api/loot/tables",
                "Create a table — {\"name\"} — or duplicate one — {\"source\", \"name\"}", this::create);
        router.get("/api/loot/validate", "Validate every table", this::validate);
        router.post("/api/loot/save", "Write the tables to disk", this::save);
        router.post("/api/loot/reload",
                "Load the tables from disk (discards anything unsaved)", this::reload);
        router.get("/api/loot/tables/{name}", "A table with every entry", this::detail);
        router.delete("/api/loot/tables/{name}", "Delete a table", this::delete);
        router.post("/api/loot/tables/{name}/entries",
                "Add an entry — {\"item\", \"weight\"?, \"minAmount\"?, \"maxAmount\"?, ...}",
                this::entryCreate);
        router.patch("/api/loot/tables/{name}/entries/{index}",
                "Change an entry (only the fields given)", this::entryPatch);
        router.delete("/api/loot/tables/{name}/entries/{index}", "Delete an entry", this::entryDelete);
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        // The scan radius and editor limits behind these routes belong to the Catalogue port
        // implementation, not this transport.
    }

    // ==================== tables ====================

    private ApiResponse tables(ApiRequest request) {
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        for (TableSummary summary : catalogue.tables()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", summary.name());
            entry.addProperty("entries", summary.entries());
            entry.addProperty("totalWeight", summary.totalWeight());
            entry.addProperty("usage", summary.usage());
            array.add(entry);
        }
        json.add("tables", array);
        json.addProperty("dirty", catalogue.isDirty());
        return ApiResponse.json(json);
    }

    private ApiResponse detail(ApiRequest request) {
        Optional<TableDetail> table = catalogue.table(request.param("name"));
        if (table.isEmpty()) {
            return ApiResponse.notFound("Unknown loot table: " + request.param("name"));
        }
        TableDetail detail = table.get();
        JsonObject json = new JsonObject();
        json.addProperty("name", detail.name());
        json.addProperty("usage", detail.usage());
        json.addProperty("totalWeight", detail.totalWeight());
        JsonArray entries = new JsonArray();
        for (int i = 0; i < detail.entries().size(); i++) {
            entries.add(entryJson(i, detail.entries().get(i)));
        }
        json.add("entries", entries);
        json.addProperty("dirty", catalogue.isDirty());
        return ApiResponse.json(json);
    }

    private ApiResponse create(ApiRequest request) {
        String name = request.requireString("name");
        String source = request.optString("source", null);
        Optional<String> error = source == null
                ? catalogue.createTable(name)
                : catalogue.duplicateTable(source, name);
        if (error.isPresent()) {
            return ApiResponse.conflict(error.get());
        }
        support.log("Loot table \"" + name + "\" "
                + (source == null ? "created" : "duplicated from \"" + source + "\"")
                + " via the HTTP API");
        return ApiResponse.ok(json -> json.addProperty("name", name));
    }

    private ApiResponse delete(ApiRequest request) {
        String name = request.param("name");
        if (!catalogue.deleteTable(name)) {
            return ApiResponse.conflict("Loot table \"" + name + "\" does not exist, or is in use");
        }
        support.log("Loot table \"" + name + "\" deleted via the HTTP API");
        return ApiResponse.ok();
    }

    private ApiResponse validate(ApiRequest request) {
        List<String> problems = catalogue.validateAll();
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        problems.forEach(array::add);
        json.add("problems", array);
        json.addProperty("valid", problems.isEmpty());
        json.addProperty("dirty", catalogue.isDirty());
        return ApiResponse.json(json);
    }

    private ApiResponse save(ApiRequest request) {
        List<String> errors = catalogue.save(ApiSupport.ACTOR);
        if (!errors.isEmpty()) {
            JsonObject json = new JsonObject();
            json.addProperty("error", String.join("; ", errors));
            JsonArray array = new JsonArray();
            errors.forEach(array::add);
            json.add("errors", array);
            return new ApiResponse(409, json);
        }
        return ApiResponse.ok();
    }

    private ApiResponse reload(ApiRequest request) {
        catalogue.reloadFromDisk();
        support.log("Loot tables reloaded from disk via the HTTP API");
        return ApiResponse.ok();
    }

    // ==================== entries ====================

    private ApiResponse entryCreate(ApiRequest request) {
        String table = request.param("name");
        if (catalogue.table(table).isEmpty()) {
            return ApiResponse.notFound("Unknown loot table: " + table);
        }
        EntryData entry = entryFrom(request, EMPTY_ENTRY);
        Optional<String> problem = catalogue.validateEntry(entry);
        if (problem.isPresent()) {
            return ApiResponse.badRequest(problem.get());
        }
        int index = catalogue.addEntry(table, entry);
        support.log("Loot table \"" + table + "\": entry " + entry.item() + " added via the HTTP API");
        return ApiResponse.ok(json -> json.add("entry", entryJson(index, entry)));
    }

    private ApiResponse entryPatch(ApiRequest request) {
        String table = request.param("name");
        Optional<TableDetail> detail = catalogue.table(table);
        if (detail.isEmpty()) {
            return ApiResponse.notFound("Unknown loot table: " + table);
        }
        int index = index(request);
        List<EntryData> entries = detail.get().entries();
        if (index < 0 || index >= entries.size()) {
            return ApiResponse.notFound("No entry at index " + index);
        }
        EntryData candidate = entryFrom(request, entries.get(index));
        Optional<String> problem = catalogue.validateEntry(candidate);
        if (problem.isPresent()) {
            return ApiResponse.badRequest(problem.get());
        }
        catalogue.replaceEntry(table, index, candidate);
        support.log("Loot table \"" + table + "\": entry " + index + " changed via the HTTP API");
        return ApiResponse.ok(json -> json.add("entry", entryJson(index, candidate)));
    }

    private ApiResponse entryDelete(ApiRequest request) {
        String table = request.param("name");
        Optional<TableDetail> detail = catalogue.table(table);
        if (detail.isEmpty()) {
            return ApiResponse.notFound("Unknown loot table: " + table);
        }
        int index = index(request);
        List<EntryData> entries = detail.get().entries();
        if (index < 0 || index >= entries.size()) {
            return ApiResponse.notFound("No entry at index " + index);
        }
        if (!catalogue.deleteEntry(table, index)) {
            return ApiResponse.conflict("Could not delete entry " + index);
        }
        support.log("Loot table \"" + table + "\": entry " + entries.get(index).item()
                + " deleted via the HTTP API");
        return ApiResponse.ok();
    }

    // ==================== internal ====================

    private static final EntryData EMPTY_ENTRY =
            new EntryData("", false, 1, 1, 1, true, false, null, List.of(), List.of());

    /** The entry from the request, over {@code base} for any field left out. */
    private static EntryData entryFrom(ApiRequest request, EntryData base) {
        String item = request.has("item") ? request.requireString("item") : base.item();
        boolean custom = request.optBool("custom", base.custom());
        int weight = request.optInt("weight", base.weight());
        int minAmount = request.optInt("minAmount", base.minAmount());
        int maxAmount = request.optInt("maxAmount", Math.max(base.maxAmount(), minAmount));
        boolean enabled = request.optBool("enabled", base.enabled());
        boolean unbreakable = request.optBool("unbreakable", base.unbreakable());
        String displayName = request.has("displayName")
                ? request.requireString("displayName") : base.displayName();
        List<String> lore = request.has("lore") ? stringList(request, "lore") : base.lore();
        List<String> enchantments = request.has("enchantments")
                ? stringList(request, "enchantments") : base.enchantments();
        return new EntryData(item, custom, weight, minAmount, maxAmount, enabled, unbreakable,
                displayName, lore, enchantments);
    }

    private static List<String> stringList(ApiRequest request, String field) {
        JsonElement element = request.body().get(field);
        if (!element.isJsonArray()) {
            throw new ApiBadRequestException("Field \"" + field + "\" must be a list");
        }
        List<String> values = new ArrayList<>();
        element.getAsJsonArray().forEach(item -> values.add(item.getAsString()));
        return values;
    }

    private static JsonObject entryJson(int index, EntryData entry) {
        JsonObject json = new JsonObject();
        json.addProperty("index", index);
        json.addProperty("item", entry.item());
        json.addProperty("custom", entry.custom());
        json.addProperty("weight", entry.weight());
        json.addProperty("minAmount", entry.minAmount());
        json.addProperty("maxAmount", entry.maxAmount());
        json.addProperty("enabled", entry.enabled());
        json.addProperty("unbreakable", entry.unbreakable());
        json.addProperty("displayName", entry.displayName());
        JsonArray lore = new JsonArray();
        entry.lore().forEach(lore::add);
        json.add("lore", lore);
        JsonArray enchantments = new JsonArray();
        entry.enchantments().forEach(enchantments::add);
        json.add("enchantments", enchantments);
        return json;
    }

    private static int index(ApiRequest request) {
        try {
            return Integer.parseInt(request.param("index"));
        } catch (NumberFormatException e) {
            throw new ApiBadRequestException("The index must be a number: " + request.param("index"));
        }
    }
}
