package de.raindancer.modules.speedrun;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Each player's own choices — for now where they see the splits — kept across restarts in
 * {@code players.yml}. A player who never chose follows {@code hud-default}, and keeps following
 * it when a host changes it.
 */
public final class SpeedrunPlayerPrefs {

    private final YamlStore store;
    private final Executor writer;
    private final Map<UUID, SpeedrunHudMode> hud = new ConcurrentHashMap<>();
    private volatile boolean readOnly;

    public SpeedrunPlayerPrefs(YamlStore store, Executor writer) {
        this.store = store;
        this.writer = writer;
    }

    public void load() {
        hud.clear();
        readOnly = false;
        if (store == null || !store.exists()) {
            return;
        }
        YamlConfiguration file = store.read();
        if (!store.problems().isEmpty()) {
            readOnly = true;   // never replace a file we could not read with one choice in it
            return;
        }
        ConfigurationSection section = file.getConfigurationSection("hud");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                hud.put(UUID.fromString(key),
                        SpeedrunHudMode.valueOf(section.getString(key, "").toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unreadable) {
                // a hand-edited line: that player simply follows the default again
            }
        }
    }

    /** Where {@code player} sees the splits. */
    public SpeedrunHudMode hudOf(UUID player, SpeedrunHudMode fallback) {
        return Optional.ofNullable(player == null ? null : hud.get(player)).orElse(fallback);
    }

    /** Whether {@code player} picked one, rather than following the default. */
    public boolean chose(UUID player) {
        return player != null && hud.containsKey(player);
    }

    public void hud(UUID player, SpeedrunHudMode mode) {
        if (player == null || mode == null) {
            return;
        }
        hud.put(player, mode);
        if (store == null || readOnly) {
            return;
        }
        writer.execute(() -> store.update(file -> file.set("hud." + player, mode.name())));
    }
}
