package de.raindancer.modules.essentials.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.essentials.rules.NicknameRule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Names nobody may wear as a nickname, in sections an owner can switch off independently.
 *
 * <h2>Why a plain YAML file rather than one more settings field</h2>
 * A list of names read and edited constantly is a worse fit for the settings framework's one
 * comma-joined line per field than for a file exactly shaped like the thing it holds — and
 * "sections you can turn off" is a structure {@code List<String>} cannot express at all without
 * inventing an encoding nobody could hand-edit. This is that file: sections, each with its own
 * {@code enabled} switch and its own {@link NicknameRule.BlockMatch}, meant to be opened in a text
 * editor.
 *
 * <h2>One list for the network</h2>
 * It is a document of the network ({@code YamlStore.network}), kept as the text it is so the owner's comments
 * and line format survive: the owner edits it once, in game, and every server reloads it when it changes
 * ({@link #watch()}). An edit is applied to the list as it is now, not as this server last read it. A list
 * that cannot be read or parsed leaves the one held as it is — never an empty blocklist.
 */
public final class NicknameBlocklist {

    private static final LogChannel log = Log.of("essentials");

    /** One section: on or off, what it does when matched, and the names in it. */
    public record Category(String id, boolean enabled, NicknameRule.BlockMatch action,
                           Set<String> names) {
    }

    private final YamlStore store;
    private final Supplier<InputStream> defaultResource;

    private volatile Map<String, Category> categories = Map.of();
    /**
     * The document's own leading comment block, captured at load and written back on every save — the
     * one piece of hand-written explanation worth keeping through an in-game edit, even though a
     * rewritten YAML document cannot keep comments anywhere else in it.
     */
    private volatile String header = "";

    public NicknameBlocklist(Path file, Supplier<InputStream> defaultResource) {
        this(YamlStore.network(file), defaultResource);
    }

    public NicknameBlocklist(YamlStore store, Supplier<InputStream> defaultResource) {
        this.store = store;
        this.defaultResource = defaultResource;
    }

    /**
     * Seeds the bundled starting list if nothing is there yet, then reads whatever is there — the owner's own
     * edits included, in full, whether or not they still resemble what shipped.
     *
     * @return false when it could not be read or parsed; what is held stays
     */
    public synchronized boolean load() {
        Optional<String> text = store.readText();
        if (text.isEmpty()) {
            seed();
            text = store.readText();
        }
        if (text.isEmpty()) {
            log.error("The blocklist could not be read; what is held stays in force until it can be.");
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text.get());
        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
            log.error(broken, "The blocklist does not parse as YAML; what is held stays in force until it does.");
            return false;
        }
        header = leadingComment(text.get());
        categories = parse(yaml);
        return true;
    }

    /** Writes the bundled list when there is no document — never over one, which another server may have just seeded. */
    private void seed() {
        try (InputStream bundled = defaultResource.get()) {
            if (bundled == null) {
                log.error("The bundled blocklist.yml is missing from the jar; no blocklist has been written "
                        + "and nothing is blocked until one exists.");
                return;
            }
            store.writeTextIfAbsent(new String(bundled.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            log.error(failure, "Could not write the starting blocklist; nothing is blocked until one exists.");
        }
    }

    /** Reloads when another server writes the list. Close to stop. */
    public NetworkBus.Subscription watch() {
        return store.onChange(this::load);
    }

    private static Map<String, Category> parse(YamlConfiguration yaml) {
        Map<String, Category> found = new LinkedHashMap<>();
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            boolean enabled = section.getBoolean("enabled", true);
            NicknameRule.BlockMatch action = actionOf(section.getString("action", "report"), id);
            List<String> raw = section.getStringList("names");
            Set<String> names = new java.util.LinkedHashSet<>();
            for (String name : raw) {
                if (name != null && !name.isBlank()) {
                    names.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
            found.put(id, new Category(id, enabled, action, names));
        }
        return found;
    }

    /** Every {@code #}-commented or blank line the document opens with, kept verbatim. */
    private static String leadingComment(String text) {
        StringBuilder header = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                header.append(line).append('\n');
            } else {
                break;
            }
        }
        return header.toString();
    }

    private static NicknameRule.BlockMatch actionOf(String text, String categoryId) {
        if ("ban".equalsIgnoreCase(text)) {
            return NicknameRule.BlockMatch.BANNED;
        }
        if ("report".equalsIgnoreCase(text)) {
            return NicknameRule.BlockMatch.REPORTED;
        }
        log.warn("blocklist: section '{}' has action '{}', which is neither 'report' nor "
                + "'ban' — treating it as 'report'.", categoryId, text);
        return NicknameRule.BlockMatch.REPORTED;
    }

    /**
     * Whether — and how severely — this plain-text nickname matches an enabled section.
     *
     * <p>A ban beats a report when a name sits in more than one enabled section, because the
     * consequence has to be one answer and the more severe one is the one worth acting on.
     */
    public NicknameRule.BlockMatch matchOf(String plain) {
        String lowered = plain.toLowerCase(Locale.ROOT);
        boolean reported = false;
        for (Category category : categories.values()) {
            if (!category.enabled() || !category.names().contains(lowered)) {
                continue;
            }
            if (category.action() == NicknameRule.BlockMatch.BANNED) {
                return NicknameRule.BlockMatch.BANNED;
            }
            reported = true;
        }
        return reported ? NicknameRule.BlockMatch.REPORTED : NicknameRule.BlockMatch.NONE;
    }

    /** Every section, enabled or not — for a diagnostic, or a future {@code /blocklist} command. */
    public Map<String, Category> categories() {
        return Map.copyOf(categories);
    }

    /** How many names are actually in force right now, across every enabled section. */
    public int enabledNameCount() {
        int total = 0;
        for (Category category : categories.values()) {
            if (category.enabled()) {
                total += category.names().size();
            }
        }
        return total;
    }

    // ---------------------------------------------------------------------------- editing, in-game

    /**
     * Switches one section on or off, and writes the change at once.
     *
     * @return whether there was such a section to switch
     */
    public boolean setEnabled(String categoryId, boolean enabled) {
        return edit(categoryId, current -> new Category(current.id(), enabled, current.action(), current.names()));
    }

    /**
     * Adds a name to a section, lower-cased the same way a hand-written one would be.
     *
     * @return whether this changed anything — false for a blank name or one already there
     */
    public boolean addName(String categoryId, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lowered = name.trim().toLowerCase(Locale.ROOT);
        return edit(categoryId, current -> {
            if (current.names().contains(lowered)) {
                return null;
            }
            Set<String> names = new LinkedHashSet<>(current.names());
            names.add(lowered);
            return new Category(current.id(), current.enabled(), current.action(), names);
        });
    }

    /** @return whether the name was there to remove */
    public boolean removeName(String categoryId, String name) {
        if (name == null) {
            return false;
        }
        String lowered = name.trim().toLowerCase(Locale.ROOT);
        return edit(categoryId, current -> {
            if (!current.names().contains(lowered)) {
                return null;
            }
            Set<String> names = new LinkedHashSet<>(current.names());
            names.remove(lowered);
            return new Category(current.id(), current.enabled(), current.action(), names);
        });
    }

    /**
     * Applies one change to the list as it is in the document now and writes it back.
     *
     * @param change the section changed, or null for "nothing to do"
     */
    private synchronized boolean edit(String categoryId, java.util.function.UnaryOperator<Category> change) {
        // The freshest list first: another server's edit since this one last read must not be undone by this one.
        load();
        Category current = categories.get(categoryId);
        if (current == null) {
            return false;
        }
        Category updated = change.apply(current);
        if (updated == null) {
            return false;
        }
        Map<String, Category> next = new LinkedHashMap<>(categories);
        next.put(updated.id(), updated);
        categories = next;
        save();
        return true;
    }

    /**
     * Writes every section back, with the header comment the document opened with still above them — the
     * one piece of hand-written explanation worth carrying through an in-game edit, even though nothing else
     * about its formatting survives a rewrite.
     */
    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Category category : categories.values()) {
            String path = category.id();
            yaml.set(path + ".enabled", category.enabled());
            yaml.set(path + ".action",
                    category.action() == NicknameRule.BlockMatch.BANNED ? "ban" : "report");
            yaml.set(path + ".names", new ArrayList<>(category.names()));
        }
        if (!store.writeText(header + yaml.saveToString())) {
            log.error("Could not write the blocklist; the change is in force until the next restart and then lost.");
        }
    }
}
