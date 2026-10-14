package de.raindancer.modules.ranks.store;

import de.raindancer.core.data.store.KeyedDocument;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.ranks.model.Holder;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Who holds which rank and how they want it shown, in {@code holders.yml}. Read on every chat line and every
 * tablist and nametag pass, so held in memory and readable from any thread.
 *
 * <p>One document for every server ({@code YamlStore.network}), one player per key ({@link KeyedDocument}): a change
 * is made to the player as the document has them now and writes only that player; another server's change is read
 * in; a document that cannot be read refuses changes and keeps what is held.
 */
public final class HolderBook {

    private final KeyedDocument<Holder> document;

    public HolderBook(YamlStore store) {
        this.document = new KeyedDocument<>(store, "holders", HolderBook::read, HolderBook::write);
    }

    private static Holder read(ConfigurationSection yaml, String key) {
        ConfigurationSection each = yaml.getConfigurationSection(key);
        UUID player;
        try {
            player = UUID.fromString(key);
        } catch (IllegalArgumentException notAPlayer) {
            return null;
        }
        if (each == null) {
            return null;
        }
        Holder holder = new Holder(player, new LinkedHashSet<>(each.getStringList("ranks")),
                each.getString("wears", ""), each.getBoolean("show.chat", true),
                each.getBoolean("show.nametag", true), each.getBoolean("show.tablist", true));
        return holder.isBlank() ? null : holder;
    }

    private static void write(ConfigurationSection yaml, String path, Holder holder) {
        yaml.set(path + ".ranks", holder.ranks().stream().sorted().toList());
        yaml.set(path + ".wears", holder.worn().isEmpty() ? null : holder.worn());
        yaml.set(path + ".show.chat", holder.chat());
        yaml.set(path + ".show.nametag", holder.nametag());
        yaml.set(path + ".show.tablist", holder.tablist());
    }

    /** Reads the document; a read that fails keeps what is held and refuses changes until one succeeds. */
    public void load() {
        document.load();
    }

    /** Told when another server changed somebody's ranks (they are read in already). Close to stop. */
    public NetworkBus.Subscription watch() {
        return document.onChange(changed -> { });
    }

    public boolean readable() {
        return document.readable();
    }

    public Holder of(UUID player) {
        return find(player).orElseGet(() -> Holder.fresh(player));
    }

    public Optional<Holder> find(UUID player) {
        return player == null ? Optional.empty() : document.get(player.toString());
    }

    public Map<UUID, Holder> all() {
        Map<UUID, Holder> all = new HashMap<>();
        document.all().values().forEach(holder -> all.put(holder.player(), holder));
        return Map.copyOf(all);
    }

    /** Changes one player. @return false, and nothing changed, when it could not be saved */
    public boolean change(UUID player, UnaryOperator<Holder> change) {
        if (player == null) {
            return false;
        }
        return document.change(player.toString(), current -> {
            Holder changed = change.apply(current == null ? Holder.fresh(player) : current);
            return changed == null || changed.isBlank() ? null : changed;
        });
    }
}
