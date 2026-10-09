package de.raindancer.modules.essentials.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.essentials.model.HouseRule;
import de.raindancer.modules.essentials.model.RulePreset;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The server's rules ({@code rules.yml}) and the presets they can be replaced with in one go
 * ({@code rule-presets.yml}). Both are plain files an owner may also edit by hand; every in-game change is
 * written at once.
 *
 * <p>A new server starts with the bundled presets and the rules of {@code defaultPreset}. Once
 * {@code rules.yml} exists it is never filled again — an owner who removed every rule meant it.
 */
public final class RuleBook {

    private static final LogChannel log = Log.of("essentials");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final YamlStore rulesFile;
    private final Path presetsPath;
    private final YamlStore presetsFile;
    private final Supplier<InputStream> bundledPresets;
    private final String defaultPreset;

    private List<HouseRule> rules = List.of();
    private Map<String, RulePreset> presets = Map.of();

    public RuleBook(Path rulesPath, Path presetsPath, Supplier<InputStream> bundledPresets, String defaultPreset) {
        this.rulesFile = new YamlStore(rulesPath);
        this.presetsPath = presetsPath;
        this.presetsFile = new YamlStore(presetsPath);
        this.bundledPresets = bundledPresets;
        this.defaultPreset = defaultPreset;
    }

    public synchronized void load() {
        if (!Files.exists(presetsPath)) {
            copyBundledPresets();
        }
        presets = readPresets(presetsFile.read());
        if (rulesFile.exists()) {
            rules = readRules(rulesFile.read().getMapList("rules"), true);
        } else {
            rules = preset(defaultPreset).map(preset -> fresh(preset.rules())).orElse(List.of());
            saveRules();
        }
    }

    // ------------------------------------------------------------------------------------------ rules

    /** Every rule, switched off ones too, in the order players see them. */
    public synchronized List<HouseRule> rules() {
        return rules;
    }

    /** What {@code /rules} shows. */
    public synchronized List<HouseRule> shown() {
        return rules.stream().filter(HouseRule::enabled).toList();
    }

    public synchronized Optional<HouseRule> byId(String id) {
        return rules.stream().filter(rule -> rule.id().equals(id)).findFirst();
    }

    /** @param number counting from one, over every rule — the number the editor shows */
    public synchronized Optional<HouseRule> byNumber(int number) {
        return number < 1 || number > rules.size() ? Optional.empty() : Optional.of(rules.get(number - 1));
    }

    public synchronized HouseRule add(String title, String text) {
        HouseRule rule = new HouseRule(newId(), title, text, HouseRule.DEFAULT_ICON, true);
        List<HouseRule> next = new ArrayList<>(rules);
        next.add(rule);
        rules = List.copyOf(next);
        saveRules();
        return rule;
    }

    /** A copy of a rule from somewhere else — a preset — at the end, with an id of its own. */
    public synchronized HouseRule addCopy(HouseRule from) {
        HouseRule rule = from.withId(newId()).withEnabled(true);
        List<HouseRule> next = new ArrayList<>(rules);
        next.add(rule);
        rules = List.copyOf(next);
        saveRules();
        return rule;
    }

    /** Whether a rule saying the same thing — same title and text, whatever the case — is already in use. */
    public synchronized boolean has(HouseRule rule) {
        return rules.stream().anyMatch(own -> own.title().equalsIgnoreCase(rule.title())
                && own.text().equalsIgnoreCase(rule.text()));
    }

    /** @return whether there was such a rule */
    public synchronized boolean update(String id, UnaryOperator<HouseRule> change) {
        List<HouseRule> next = new ArrayList<>(rules);
        for (int index = 0; index < next.size(); index++) {
            if (next.get(index).id().equals(id)) {
                next.set(index, change.apply(next.get(index)).withId(id));
                rules = List.copyOf(next);
                saveRules();
                return true;
            }
        }
        return false;
    }

    public synchronized Optional<HouseRule> remove(String id) {
        Optional<HouseRule> found = byId(id);
        found.ifPresent(rule -> {
            rules = rules.stream().filter(other -> !other.id().equals(id)).toList();
            saveRules();
        });
        return found;
    }

    /** @param by -1 up, +1 down; false when it is already at that end */
    public synchronized boolean move(String id, int by) {
        int from = indexOf(id);
        int to = from + by;
        if (from < 0 || to < 0 || to >= rules.size()) {
            return false;
        }
        List<HouseRule> next = new ArrayList<>(rules);
        next.add(to, next.remove(from));
        rules = List.copyOf(next);
        saveRules();
        return true;
    }

    // ---------------------------------------------------------------------------------------- presets

    public synchronized List<RulePreset> presets() {
        return List.copyOf(presets.values());
    }

    public synchronized Optional<RulePreset> preset(String name) {
        return Optional.ofNullable(presets.get(name));
    }

    /** Replaces every rule with a copy of the preset's. */
    public synchronized boolean applyPreset(String name) {
        RulePreset preset = presets.get(name);
        if (preset == null) {
            return false;
        }
        rules = fresh(preset.rules());
        saveRules();
        return true;
    }

    /** Keeps the current rules as a preset, replacing one of the same name. */
    public synchronized boolean savePreset(String name, String description) {
        Map<String, RulePreset> next = new LinkedHashMap<>(presets);
        next.put(name, new RulePreset(name, description, rules));
        presets = next;
        return savePresets();
    }

    public synchronized boolean deletePreset(String name) {
        if (!presets.containsKey(name)) {
            return false;
        }
        Map<String, RulePreset> next = new LinkedHashMap<>(presets);
        next.remove(name);
        presets = next;
        return savePresets();
    }

    // ------------------------------------------------------------------------------------------- disk

    private void copyBundledPresets() {
        try (InputStream bundled = bundledPresets.get()) {
            if (bundled == null) {
                log.error("The bundled rule-presets.yml is missing from the jar; there are no presets until "
                        + "one exists at {}.", presetsPath);
                return;
            }
            Path parent = presetsPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(bundled, presetsPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            log.error(failure, "Could not write the starting rule presets to {}.", presetsPath);
        }
    }

    private static Map<String, RulePreset> readPresets(YamlConfiguration yaml) {
        Map<String, RulePreset> found = new LinkedHashMap<>();
        for (String name : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(name);
            if (section != null) {
                found.put(name, new RulePreset(name, section.getString("description", ""),
                        readRules(section.getMapList("rules"), false)));
            }
        }
        return found;
    }

    private static List<HouseRule> readRules(List<Map<?, ?>> entries, boolean withIds) {
        List<HouseRule> found = new ArrayList<>();
        for (Map<?, ?> entry : entries) {
            Object id = entry.get("id");
            Object enabled = entry.get("enabled");
            found.add(new HouseRule(withIds && id != null ? String.valueOf(id) : null,
                    text(entry.get("title")), text(entry.get("text")), material(entry.get("icon")),
                    !(enabled instanceof Boolean flag) || flag));
        }
        return withIds ? withUniqueIds(found) : found;
    }

    /** A hand-written rule without an id, or one copied with its id, gets one of its own. */
    private static List<HouseRule> withUniqueIds(List<HouseRule> read) {
        List<HouseRule> fixed = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (HouseRule rule : read) {
            fixed.add(rule.id() == null || rule.id().isBlank() || !seen.add(rule.id())
                    ? rule.withId(newIdNotIn(seen)) : rule);
        }
        return List.copyOf(fixed);
    }

    private static String newIdNotIn(java.util.Set<String> taken) {
        String id;
        do {
            id = Long.toString(RANDOM.nextLong(36L * 36 * 36 * 36 * 36 * 36), 36);
        } while (!taken.add(id));
        return id;
    }

    private String newId() {
        java.util.Set<String> taken = new java.util.HashSet<>();
        rules.forEach(rule -> taken.add(rule.id()));
        return newIdNotIn(taken);
    }

    private List<HouseRule> fresh(List<HouseRule> from) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        List<HouseRule> copies = new ArrayList<>();
        for (HouseRule rule : from) {
            copies.add(rule.withId(newIdNotIn(taken)));
        }
        return List.copyOf(copies);
    }

    private int indexOf(String id) {
        for (int index = 0; index < rules.size(); index++) {
            if (rules.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Material material(Object value) {
        Material found = value == null ? null : Material.matchMaterial(String.valueOf(value));
        return found == null ? HouseRule.DEFAULT_ICON : found;
    }

    private static List<Map<String, Object>> written(List<HouseRule> rules, boolean withIds) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (HouseRule rule : rules) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (withIds) {
                entry.put("id", rule.id());
            }
            entry.put("title", rule.title());
            entry.put("text", rule.text());
            entry.put("icon", rule.icon().name().toLowerCase(Locale.ROOT));
            if (withIds || !rule.enabled()) {
                entry.put("enabled", rule.enabled());
            }
            entries.add(entry);
        }
        return entries;
    }

    private void saveRules() {
        List<Map<String, Object>> entries = written(rules, true);
        if (!rulesFile.write(yaml -> {
            yaml.options().setHeader(List.of(
                    "The server's rules, in the order /rules shows them. Edit in game with /rules edit,",
                    "or here and restart. 'enabled: false' keeps a rule without showing it."));
            yaml.set("rules", entries);
        })) {
            log.error("Could not write {}; the change to the rules is lost on the next restart.", rulesFile.file());
        }
    }

    private boolean savePresets() {
        Map<String, RulePreset> snapshot = presets;
        boolean saved = presetsFile.write(yaml -> snapshot.forEach((name, preset) -> {
            if (!preset.description().isEmpty()) {
                yaml.set(name + ".description", preset.description());
            }
            yaml.set(name + ".rules", written(preset.rules(), false));
        }));
        if (!saved) {
            log.error("Could not write {}; the change to the presets is lost on the next restart.", presetsPath);
        }
        return saved;
    }
}
