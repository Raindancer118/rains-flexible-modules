package de.raindancer.modules.essentials.store;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.essentials.rules.NicknameRule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <h2>Why it is read once rather than watched</h2>
 * It is not a setting a server flips at runtime the way AFK's timeout is — it is closer to
 * {@code permissions.yml}: something an owner edits and restarts for. Loaded once, when the module
 * enables, the same as {@link EssentialsStore}.
 */
public final class NicknameBlocklist {

    private static final LogChannel log = Log.of("essentials");

    /** One section: on or off, what it does when matched, and the names in it. */
    public record Category(String id, boolean enabled, NicknameRule.BlockMatch action,
                           Set<String> names) {
    }

    private final Path file;
    private final Supplier<InputStream> defaultResource;

    private volatile Map<String, Category> categories = Map.of();

    public NicknameBlocklist(Path file, Supplier<InputStream> defaultResource) {
        this.file = file;
        this.defaultResource = defaultResource;
    }

    /**
     * Writes the bundled starting file if nothing is there yet, then reads whatever is on disk —
     * the owner's own edits included, in full, whether or not they still resemble what shipped.
     */
    public void load() {
        if (!Files.exists(file)) {
            copyDefault();
        }
        categories = read();
    }

    private void copyDefault() {
        try (InputStream bundled = defaultResource.get()) {
            if (bundled == null) {
                log.error("The bundled blocklist.yml is missing from the jar; no blocklist file "
                        + "has been written and nothing is blocked until one exists at {}.", file);
                return;
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(bundled, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            log.error(failure, "Could not write the starting blocklist to {}; nothing is blocked "
                    + "until one exists there.", file);
        }
    }

    private Map<String, Category> read() {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            log.error(unreadable, "Could not read {}; nothing is blocked until it can be.", file);
            return Map.of();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (org.bukkit.configuration.InvalidConfigurationException broken) {
            log.error(broken, "{} does not parse as YAML; nothing is blocked until it does.", file);
            return Map.of();
        }

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

    private static NicknameRule.BlockMatch actionOf(String text, String categoryId) {
        if ("ban".equalsIgnoreCase(text)) {
            return NicknameRule.BlockMatch.BANNED;
        }
        if ("report".equalsIgnoreCase(text)) {
            return NicknameRule.BlockMatch.REPORTED;
        }
        log.warn("blocklist.yml: section '{}' has action '{}', which is neither 'report' nor "
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
}
