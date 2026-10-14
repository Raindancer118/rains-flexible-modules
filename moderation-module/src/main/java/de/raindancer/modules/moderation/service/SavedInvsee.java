package de.raindancer.modules.moderation.service;

import de.raindancer.core.data.inventory.SavedInventories;
import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.moderation.invsee.Access;
import de.raindancer.core.moderation.invsee.Carried;
import de.raindancer.core.moderation.invsee.Inventories;
import de.raindancer.core.moderation.invsee.InventorySource;
import de.raindancer.core.moderation.invsee.InventoryWindow;
import de.raindancer.core.moderation.invsee.Section;
import de.raindancer.core.moderation.invsee.Slots;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.ModerationSettings;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Looking at the inventory this server last saved for somebody who is not on it, read only.
 *
 * <p>Inventories are each server's own, so a player who is on another server, or on none, has no inventory here to
 * open. What the network keeps is {@link SavedInventories}: each server's last copy. This shows this server's and
 * never writes: the window it opens is {@link Access#READ_ONLY} over a source that refuses every write, and nothing
 * here touches any server's copy.
 */
public final class SavedInvsee implements IModerationService {

    private static final LogChannel log = Log.of("moderation");

    /** One server's copy, ready to be looked at. */
    public record Copy(String server, long savedAt, Carried<ItemStack> carried) {
    }

    private final Plugin plugin;
    private final Inventories inventories;
    private final Audit audit;
    private final Messages messages;
    private final Supplier<SavedInventories> store;
    private final Function<byte[], ItemStack[]> decoder;
    private final BooleanSupplier network;

    public SavedInvsee(Plugin plugin, Inventories inventories, Audit audit, Messages messages,
                       Supplier<SavedInventories> store, BooleanSupplier network) {
        this(plugin, inventories, audit, messages, store, ItemStack::deserializeItemsFromBytes, network);
    }

    SavedInvsee(Plugin plugin, Inventories inventories, Audit audit, Messages messages,
                Supplier<SavedInventories> store, Function<byte[], ItemStack[]> decoder, BooleanSupplier network) {
        this.plugin = plugin;
        this.inventories = inventories;
        this.audit = audit;
        this.messages = messages;
        this.store = store;
        this.decoder = decoder;
        this.network = network;
    }

    @Override
    public void settings(ModerationSettings settings) {
        // Nothing here reads a setting.
    }

    /** Whether somebody not on this server is looked at through saved copies: only on a network. */
    public boolean wanted() {
        return network.getAsBoolean();
    }

    /**
     * The copy {@code server} — this one — saved of {@code player}, off the server's threads: their inventory here,
     * whichever server they are on now. Empty when there is none, or it cannot be read.
     */
    public Optional<Copy> here(UUID player, String server) {
        try {
            return store.get().on(server, player).map(saved -> new Copy(saved.server(), saved.savedAt(),
                    carriedOf(decoder.apply(saved.contents()), decoder.apply(saved.enderChest()))));
        } catch (RuntimeException unavailable) {
            log.warn("The saved inventory of {} could not be read: {}", player, unavailable.toString());
            return Optional.empty();
        }
    }

    /** The parts of an inventory array as the game stores it, and of an ender chest. */
    static Carried<ItemStack> carriedOf(ItemStack[] contents, ItemStack[] enderChest) {
        Carried<ItemStack> carried = Carried.empty();
        for (Section section : Section.values()) {
            ItemStack[] from = section.isSeparate() ? enderChest : contents;
            for (int within = 0; within < section.size(); within++) {
                int at = section.isSeparate() ? within : Slots.rawSlot(section, within);
                if (from != null && at < from.length && from[at] != null) {
                    carried = carried.with(section, within, from[at]);
                }
            }
        }
        return carried;
    }

    /** Opens the window for {@code watcher}: on their own thread. False when somebody else already has it open for editing. */
    public boolean show(Player watcher, UUID owner, String ownerName, Copy copy) {
        if (!inventories.views().open(watcher.getUniqueId(), owner, Access.READ_ONLY)) {
            return false;
        }
        InventoryWindow window = new InventoryWindow(plugin, watcher, owner, ownerName, Access.READ_ONLY, false,
                new ReadOnly(copy.carried()), copy.carried());
        window.audit(audit);
        window.messages(inventories.messages());
        window.enderChest(true);
        window.open();
        if (audit != null) {
            audit.record(AuditEntry.of("invsee", "looked inside")
                    .by(watcher.getUniqueId(), watcher.getName())
                    .to(owner, ownerName)
                    .saying(Access.READ_ONLY.saying(messages) + ", saved copy from " + copy.server())
                    .with("access", Access.READ_ONLY.name())
                    .with("source", "saved:" + copy.server()));
        }
        return true;
    }

    /** A source that is only ever read: nothing a click does reaches anything. */
    static final class ReadOnly implements InventorySource {

        private final Carried<ItemStack> carried;

        ReadOnly(Carried<ItemStack> carried) {
            this.carried = carried;
        }

        @Override
        public Optional<Carried<ItemStack>> read(UUID who) {
            return Optional.of(carried);
        }

        @Override
        public boolean set(UUID who, Section section, int indexWithin, ItemStack item) {
            return false;
        }

        @Override
        public boolean write(UUID who, Carried<ItemStack> carried) {
            return false;
        }

        @Override
        public String describe() {
            return "a saved copy";
        }
    }

    @Override
    public String describe() {
        return "looking at the saved inventory of somebody who is not on this server";
    }
}
