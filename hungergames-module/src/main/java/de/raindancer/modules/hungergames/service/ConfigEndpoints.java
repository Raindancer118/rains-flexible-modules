package de.raindancer.modules.hungergames.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.raindancer.core.data.settings.Setting;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.settings.SettingsTopic;
import de.raindancer.modules.hungergames.HungerGamesSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration endpoints: read every setting, one or several at a time, list the topics they are filed
 * under, and reload from disk.
 *
 * <h2>Why this needed no port</h2>
 * Unlike the other endpoint classes in this package, every operation here is genuinely complete today:
 * {@link SettingsStore} and {@link SettingsSchema} are Core, already built, and already the one true
 * source of what this module's settings are — there is nothing left for a future service to plug in.
 *
 * <h2>Secret values are masked, never omitted</h2>
 * A key in {@link #SECRET_KEYS} — {@code api.key} — is never sent back as text: a dashboard built against
 * this API would otherwise leak the very key it needs to authenticate. The key stays visible as a field so
 * a caller can tell it exists and whether it has been set, and can still be <em>written</em>, because
 * changing a key one cannot read back is still a normal thing to want to do.
 */
final class ConfigEndpoints implements ApiRouter.Module, IHungerGamesService {

    /** Keys whose values are never sent back as text. */
    static final Set<String> SECRET_KEYS = Set.of("api.key");

    /** What a masked value reads as. */
    static final String MASKED = "***";

    private final ApiSupport support;
    private final SettingsStore<HungerGamesSettings> store;

    ConfigEndpoints(ApiSupport support, SettingsStore<HungerGamesSettings> store) {
        this.support = support;
        this.store = store;
    }

    @Override
    public void register(ApiRouter router) {
        router.get("/api/config", "Every setting — ?topic=hungergames/border&changed=true", this::list);
        router.get("/api/config/categories", "Topics, with a count of settings under each",
                this::categories);
        router.get("/api/config/{key}", "One setting", this::detail);
        router.put("/api/config/{key}", "Set a value — {\"value\": \"...\"}", this::set);
        router.post("/api/config",
                "Set several values in order, stopping at the first invalid one — "
                        + "{\"values\": {...}}", this::setMany);
        router.post("/api/config/reload", "Reload config.yml from disk", this::reload);
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        // This class reads the live SettingsStore directly rather than a pushed snapshot: a snapshot
        // taken at the last reload would make PUT /api/config/{key} write into a value that then read
        // back stale until the next settings() call. The store is Core's own live view for exactly
        // this reason. Declared anyway, so nothing here can be mistaken for the one service that
        // forgot to.
    }

    // ==================== reading ====================

    private ApiResponse list(ApiRequest request) {
        String topicFilter = request.queryString("topic", null);
        boolean onlyChanged = request.queryBool("changed", false);

        JsonArray array = new JsonArray();
        for (Setting<?> setting : store.schema().settings()) {
            if (topicFilter != null && !setting.topicPath().equalsIgnoreCase(topicFilter)) {
                continue;
            }
            JsonObject entry = settingJson(setting);
            if (onlyChanged && !entry.get("changed").getAsBoolean()) {
                continue;
            }
            array.add(entry);
        }
        return ApiResponse.json("settings", array);
    }

    private ApiResponse categories(ApiRequest request) {
        JsonArray array = new JsonArray();
        for (SettingsTopic topic : store.schema().topics().all()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", topic.path());
            entry.addProperty("title", topic.title());
            entry.addProperty("settings", topic.settings().size());
            array.add(entry);
        }
        return ApiResponse.json("categories", array);
    }

    private ApiResponse detail(ApiRequest request) {
        return setting(request.param("key"))
                .map(setting -> ApiResponse.json(settingJson(setting)))
                .orElseGet(() -> ApiResponse.notFound("Unknown setting: " + request.param("key")));
    }

    // ==================== writing ====================

    private ApiResponse set(ApiRequest request) {
        String key = request.param("key");
        if (setting(key).isEmpty()) {
            return ApiResponse.notFound("Unknown setting: " + key);
        }
        String value = request.requireString("value");
        return apply(Map.of(key, value));
    }

    private ApiResponse setMany(ApiRequest request) {
        if (!request.has("values") || !request.body().get("values").isJsonObject()) {
            return ApiResponse.badRequest("Expected a JSON body: {\"values\": {\"key\": \"value\", ...}}");
        }
        JsonObject values = request.body().getAsJsonObject("values");
        Map<String, String> changes = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String key : values.keySet()) {
            if (setting(key).isEmpty()) {
                unknown.add(key);
            } else {
                changes.put(key, values.get(key).getAsString());
            }
        }
        if (!unknown.isEmpty()) {
            return ApiResponse.badRequest("Unknown setting(s): " + String.join(", ", unknown));
        }
        if (changes.isEmpty()) {
            return ApiResponse.badRequest("No values were given");
        }
        return apply(changes);
    }

    /**
     * Applies each change in order, stopping at the first one {@link SettingsStore#set} refuses.
     *
     * <p>Not a transaction: {@link SettingsStore} validates and writes in the same call, and adding a
     * second, this-endpoint-only codec path to check a value without applying it would be exactly the
     * kind of second answer {@code ReuseTest} exists to catch. What is saved instead is that a refusal
     * never leaves the caller guessing — the response names which keys landed before the one that did
     * not, in {@code applied}, so a caller who sent five values and got a 400 knows precisely which three
     * took effect rather than having to re-read all five back.
     */
    private ApiResponse apply(Map<String, String> changes) {
        List<String> applied = new ArrayList<>();
        for (Map.Entry<String, String> change : changes.entrySet()) {
            if (!store.set(change.getKey(), change.getValue())) {
                if (!applied.isEmpty()) {
                    store.save();
                }
                List<String> landed = List.copyOf(applied);
                JsonObject json = new JsonObject();
                json.addProperty("error", "\"" + change.getValue() + "\" is not a valid value for "
                        + change.getKey());
                json.add("applied", toArray(landed));
                return new ApiResponse(400, json);
            }
            applied.add(change.getKey());
        }
        store.save();
        changes.forEach((key, value) -> support.log("Configuration " + key + " = "
                + (SECRET_KEYS.contains(key) ? MASKED : value) + " via the HTTP API"));
        List<String> allApplied = List.copyOf(applied);
        return ApiResponse.ok(json -> json.add("applied", toArray(allApplied)));
    }

    private static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private ApiResponse reload(ApiRequest request) {
        store.load();
        support.log("Configuration reloaded from disk via the HTTP API");
        return ApiResponse.ok();
    }

    // ==================== internal ====================

    private Optional<Setting<?>> setting(String key) {
        return store.schema().setting(key);
    }

    private JsonObject settingJson(Setting<?> setting) {
        boolean secret = SECRET_KEYS.contains(setting.key());
        JsonObject json = new JsonObject();
        json.addProperty("key", setting.key());
        json.addProperty("topic", setting.topicPath());
        json.addProperty("title", setting.title());
        json.addProperty("description", setting.description());
        json.addProperty("type", setting.type().getSimpleName());
        json.addProperty("secret", secret);
        String value = store.display(setting.key());
        String defaultValue = String.valueOf(setting.defaultValue());
        json.addProperty("value", secret ? maskIfSet(value) : value);
        json.addProperty("default", secret ? maskIfSet(defaultValue) : defaultValue);
        json.addProperty("changed", !value.equals(defaultValue));
        return json;
    }

    private static String maskIfSet(String value) {
        return value == null || value.isEmpty() ? "" : MASKED;
    }
}
