package de.raindancer.modules.roles.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.roles.model.Choice;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Who took which role, and when, in {@code choices.yml}. Held in memory — a shop asks for every price it
 * draws.
 *
 * <h2>On a network</h2>
 * One document for every server ({@code YamlStore.network}): a role is the player's wherever they play. A change
 * writes only that player's entry, into the document as it is now, so two servers changing different players lose
 * nothing of each other's. {@link #watch()} reloads when another server writes and says whose choice differs now,
 * so what a role puts on a player can follow it.
 *
 * <p>A document that could not be read is never written over, and a failed read keeps what is held: until it can
 * be read nobody can change role.
 */
public final class ChoiceBook {

    private final YamlStore store;
    private final Map<UUID, Choice> choices = new HashMap<>();
    private volatile boolean readable = true;
    private volatile Consumer<Set<UUID>> elsewhere = ignored -> { };

    public ChoiceBook(YamlStore store) {
        this.store = store;
    }

    /** Reads the document; a read that fails keeps what is held and refuses changes until one succeeds. */
    public synchronized void load() {
        reading();
    }

    /** @return the players whose choice is not what it was */
    private synchronized Set<UUID> reading() {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            readable = false;
            return Set.of();
        }
        readable = true;
        Map<UUID, Choice> fresh = new HashMap<>();
        ConfigurationSection all = read.get().getConfigurationSection("choices");
        if (all != null) {
            for (String key : all.getKeys(false)) {
                ConfigurationSection each = all.getConfigurationSection(key);
                String role = each == null ? null : each.getString("role");
                if (role == null || role.isBlank()) {
                    continue;
                }
                try {
                    UUID player = UUID.fromString(key);
                    long chosen = each.getLong("chosen-at", 0L);
                    fresh.put(player, new Choice(player, role, chosen, each.getLong("held-since", chosen)));
                } catch (IllegalArgumentException notAPlayer) {
                    // Skipped; the rest still count.
                }
            }
        }
        Set<UUID> differing = new HashSet<>();
        fresh.forEach((player, choice) -> {
            if (!choice.equals(choices.get(player))) {
                differing.add(player);
            }
        });
        choices.keySet().stream().filter(player -> !fresh.containsKey(player)).forEach(differing::add);
        choices.clear();
        choices.putAll(fresh);
        return differing;
    }

    /** Reloads when another server writes the document, then tells {@code changed} whose choice differs now. */
    public NetworkBus.Subscription watch() {
        return store.onChange(() -> {
            Set<UUID> differing = reading();
            if (!differing.isEmpty()) {
                elsewhere.accept(differing);
            }
        });
    }

    /** Told, from the thread that read the document, which players another server changed the choice of. */
    public void changedElsewhere(Consumer<Set<UUID>> listener) {
        this.elsewhere = listener == null ? ignored -> { } : listener;
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
        if (!writable()) {
            return false;
        }
        String path = "choices." + choice.player();
        if (!store.update(yaml -> {
            yaml.set(path + ".role", choice.role());
            yaml.set(path + ".chosen-at", choice.chosenAt());
            yaml.set(path + ".held-since", choice.heldSince());
        })) {
            return false;
        }
        choices.put(choice.player(), choice);
        return true;
    }

    /** Forgets a player's role. @return false, and nothing changed, when it could not be saved */
    public synchronized boolean clear(UUID player) {
        if (!writable()) {
            return false;
        }
        if (choices.get(player) == null) {
            return true;
        }
        if (!store.update(yaml -> yaml.set("choices." + player, null))) {
            return false;
        }
        choices.remove(player);
        return true;
    }

    /** Whether the document can be written: one that could not be read is tried again, and refused until it can. */
    private boolean writable() {
        if (!readable) {
            reading();
        }
        return readable;
    }
}
