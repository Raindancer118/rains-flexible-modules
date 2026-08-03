package de.raindancer.modules.moderation.util;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Putting a module's own wording into Core's {@link Messages}.
 *
 * <h2>Why not just call {@code Messages.load}</h2>
 * Because there is one {@code Messages} on the server and it is Core's. {@code load} replaces the
 * bundled layer, so a module calling it would throw away Core's own wording and every other module's
 * with it. {@link Messages#defineAll} is the layer built for exactly this: a <em>default</em>, below
 * anything the owner has written, so a module can ship a line and an owner can still change it.
 *
 * <p>Which is also why the module's keys are all under one prefix. An owner edits one
 * {@code messages.yml}, and a module that scattered bare keys through it would be a module whose lines
 * nobody could find.
 *
 * <p>Genuinely generic, hence {@code util}: nothing here knows what a punishment is, and any module
 * would want it the same way.
 */
public final class Words {

    private Words() {
    }

    /**
     * Reads a bundled {@code messages.yml} and offers every line in it as a default.
     *
     * @param bundled from {@code getResourceAsStream}; closed here, so the caller does not have to
     * @return how many lines were taken
     */
    public static int define(Messages messages, InputStream bundled) {
        if (messages == null || bundled == null) {
            return 0;
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        try (InputStream stream = bundled) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String key : yaml.getKeys(true)) {
                if (yaml.isConfigurationSection(key)) {
                    continue;   // the branches; only the leaves are messages
                }
                Object value = yaml.get(key);
                if (value != null) {
                    flat.put(key, value);
                }
            }
        } catch (Exception unreadable) {
            // Deliberately swallowed: a module whose wording will not parse should come up speaking
            // Core's fallbacks rather than refusing to start. Messages.problems() carries the rest.
            return 0;
        }
        return messages.defineAll(flat);
    }
}
