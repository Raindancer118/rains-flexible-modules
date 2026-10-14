package de.raindancer.modules.roles.store;

import de.raindancer.core.data.store.KeyedDocument;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.roles.model.Choice;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Who took which role, and when, in {@code choices.yml}. Held in memory — a shop asks for every price it draws.
 *
 * <p>One document for every server ({@code YamlStore.network}), one player per key ({@link KeyedDocument}): a role is
 * the player's wherever they play; {@link #watch()} says whose choice another server changed, so what a role puts
 * on a player can follow it. A document that cannot be read refuses changes and keeps what is held.
 */
public final class ChoiceBook {

    private final KeyedDocument<Choice> document;
    private volatile Consumer<Set<UUID>> elsewhere = ignored -> { };

    public ChoiceBook(YamlStore store) {
        this.document = new KeyedDocument<>(store, "choices", ChoiceBook::read, ChoiceBook::write);
    }

    private static Choice read(ConfigurationSection yaml, String key) {
        ConfigurationSection each = yaml.getConfigurationSection(key);
        String role = each == null ? null : each.getString("role");
        if (role == null || role.isBlank()) {
            return null;
        }
        try {
            UUID player = UUID.fromString(key);
            long chosen = each.getLong("chosen-at", 0L);
            return new Choice(player, role, chosen, each.getLong("held-since", chosen));
        } catch (IllegalArgumentException notAPlayer) {
            return null;
        }
    }

    private static void write(ConfigurationSection yaml, String path, Choice choice) {
        yaml.set(path + ".role", choice.role());
        yaml.set(path + ".chosen-at", choice.chosenAt());
        yaml.set(path + ".held-since", choice.heldSince());
    }

    /** Reads the document; a read that fails keeps what is held and refuses changes until one succeeds. */
    public void load() {
        document.load();
    }

    /** Tells {@link #changedElsewhere} whose choice another server changed, once it is read in. */
    public NetworkBus.Subscription watch() {
        return document.onChange(keys -> {
            Set<UUID> players = new HashSet<>();
            for (String key : keys) {
                try {
                    players.add(UUID.fromString(key));
                } catch (IllegalArgumentException notAPlayer) {
                    // Never written by this book.
                }
            }
            if (!players.isEmpty()) {
                elsewhere.accept(players);
            }
        });
    }

    /** Told, from the thread that read the document, which players another server changed the choice of. */
    public void changedElsewhere(Consumer<Set<UUID>> listener) {
        this.elsewhere = listener == null ? ignored -> { } : listener;
    }

    public boolean readable() {
        return document.readable();
    }

    public Optional<Choice> of(UUID player) {
        return player == null ? Optional.empty() : document.get(player.toString());
    }

    public int count() {
        return document.all().size();
    }

    /** Records a choice. @return false, and nothing changed, when it could not be saved */
    public boolean put(Choice choice) {
        return document.change(choice.player().toString(), ignored -> choice);
    }

    /** Forgets a player's role. @return false, and nothing changed, when it could not be saved */
    public boolean clear(UUID player) {
        if (document.readable() && of(player).isEmpty()) {
            return true;
        }
        return document.change(player.toString(), ignored -> null);
    }
}
