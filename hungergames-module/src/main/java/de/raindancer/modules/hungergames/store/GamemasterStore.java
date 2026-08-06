package de.raindancer.modules.hungergames.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persists {@code gamemasters.yml}: who is currently in gamemaster mode, and what to give them back if the
 * server restarts while they are in it.
 *
 * <h2>Why this survives a restart at all</h2>
 * Turning gamemaster mode on changes two things about a player — their game mode, and sometimes their
 * operator status — and both are meant to be temporary, undone the moment the gamemaster switches back off.
 * A restart in between must not turn that temporary change permanent: an admin who was de-opped for the
 * round and then never got their {@code /gm off} in because the server went down first should not stay a
 * non-operator forever. So what is kept here is exactly what {@code deactivate} needs to put back —
 * nothing about the round itself, which is {@code YamlSessionStore}'s file entirely.
 *
 * <h2>Why the whole roster is rewritten every time</h2>
 * There are, in practice, a handful of gamemasters at once, never hundreds, so there is nothing to gain
 * from a partial update and something to lose: a second writer touching only its own entry could still
 * race a first one doing the same, and a full rewrite from the in-memory set never can, because there is
 * only one in-memory set to disagree with.
 */
public final class GamemasterStore {

    private static final LogChannel log = Log.of("hungergames");

    /** What is restored when a gamemaster switches back off: the mode they had, and whether they were de-opped. */
    public record ActiveState(GameMode previousMode, boolean deopped) {
    }

    private final YamlStore store;
    private final List<String> problems = new ArrayList<>();

    public GamemasterStore(Path file) {
        this.store = new YamlStore(file);
    }

    /** What could not be read the last time this store was loaded. Empty when it was clean. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Every active gamemaster and what to restore for them. Never throws; a broken file reads as empty. */
    public Map<UUID, ActiveState> load() {
        synchronized (problems) {
            problems.clear();
        }
        if (!store.exists()) {
            return Map.of();
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            carry();
            store.quarantine();
            return Map.of();
        }
        Map<UUID, ActiveState> active = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("active");
        if (section == null) {
            return active;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                GameMode previous = GameMode.valueOf(
                        section.getString(key + ".previous-mode", "SURVIVAL").toUpperCase(Locale.ROOT));
                boolean deopped = section.getBoolean(key + ".deopped", false);
                active.put(uuid, new ActiveState(previous, deopped));
            } catch (IllegalArgumentException broken) {
                note("the entry for '" + key + "' was skipped (" + broken.getMessage() + ")");
            }
        }
        return active;
    }

    /** Writes the complete roster of active gamemasters. */
    public void save(Map<UUID, ActiveState> active) {
        store.write(yaml -> {
            for (Map.Entry<UUID, ActiveState> entry : active.entrySet()) {
                String base = "active." + entry.getKey();
                yaml.set(base + ".previous-mode", entry.getValue().previousMode().name());
                yaml.set(base + ".deopped", entry.getValue().deopped());
            }
        });
    }

    private void carry() {
        List<String> fromFile = store.problems();
        synchronized (problems) {
            problems.addAll(fromFile);
        }
    }

    private void note(String problem) {
        synchronized (problems) {
            problems.add(problem);
        }
        log.warn("gamemasters.yml: {}", problem);
    }
}
