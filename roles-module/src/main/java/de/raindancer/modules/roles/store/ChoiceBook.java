package de.raindancer.modules.roles.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.roles.model.Choice;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who took which role, and when, in {@code choices.yml}. Held in memory — a shop asks for every price it
 * draws — and written whole on every change, which is rare.
 *
 * <p>A file that could not be read is never written over: until somebody fixes it, nobody can change role,
 * because saving would replace everybody's choices with only the new one.
 */
public final class ChoiceBook {

    private final YamlStore store;
    private final Map<UUID, Choice> choices = new HashMap<>();
    private volatile boolean readable = true;

    public ChoiceBook(YamlStore store) {
        this.store = store;
    }

    public synchronized void load() {
        choices.clear();
        YamlConfiguration yaml = store.read();
        readable = store.problems().isEmpty();
        ConfigurationSection all = yaml.getConfigurationSection("choices");
        if (all == null) {
            return;
        }
        for (String key : all.getKeys(false)) {
            ConfigurationSection each = all.getConfigurationSection(key);
            String role = each == null ? null : each.getString("role");
            if (role == null || role.isBlank()) {
                continue;
            }
            try {
                UUID player = UUID.fromString(key);
                choices.put(player, new Choice(player, role, each.getLong("chosen-at", 0L)));
            } catch (IllegalArgumentException notAPlayer) {
                // Skipped; the rest still count.
            }
        }
    }

    public boolean readable() {
        return readable;
    }

    public synchronized Optional<Choice> of(UUID player) {
        return Optional.ofNullable(player == null ? null : choices.get(player));
    }

    public synchronized int count() {
        return choices.size();
    }

    /** Records a choice. @return false, and nothing changed, when it could not be saved */
    public synchronized boolean put(Choice choice) {
        if (!readable) {
            return false;
        }
        Choice before = choices.put(choice.player(), choice);
        if (save()) {
            return true;
        }
        if (before == null) {
            choices.remove(choice.player());
        } else {
            choices.put(choice.player(), before);
        }
        return false;
    }

    /** Forgets a player's role. @return false, and nothing changed, when it could not be saved */
    public synchronized boolean clear(UUID player) {
        if (!readable) {
            return false;
        }
        Choice before = choices.remove(player);
        if (before == null || save()) {
            return true;
        }
        choices.put(player, before);
        return false;
    }

    private boolean save() {
        Map<UUID, Choice> snapshot = Map.copyOf(choices);
        return store.write(yaml -> snapshot.values().forEach(choice -> {
            String path = "choices." + choice.player();
            yaml.set(path + ".role", choice.role());
            yaml.set(path + ".chosen-at", choice.chosenAt());
        }));
    }
}
