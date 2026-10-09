package de.raindancer.modules.moderation.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.core.data.stash.ArmourPiece;
import de.raindancer.modules.moderation.command.VaultCommand;
import de.raindancer.core.data.stash.Stash;
import de.raindancer.modules.moderation.rules.BanhammerRule;
import de.raindancer.core.data.stash.StashStore;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operators' personal vaults: putting things in, taking them out, and the armour stands.
 *
 * <p>Every move is "take from one side, then hand over exactly what the other side accepted", and
 * whatever does not fit goes back where it came from — never on the floor and never into nothing.
 * Each change is written to disk straight away, off the player's thread.
 */
public final class VaultService implements IModerationService {

    /** One page of the screen. */
    public static final int PER_PAGE = 36;
    public static final int PAGES = 6;
    public static final int CAPACITY = PER_PAGE * PAGES;

    private final Plugin plugin;
    private final StashStore storage;
    private final Messages messages;
    private final BanhammerRule banhammer = new BanhammerRule();
    private final Map<UUID, Stash> open = new ConcurrentHashMap<>();

    public VaultService(Plugin plugin, StashStore storage, Messages messages, ModerationSettings settings) {
        this.plugin = plugin;
        this.storage = storage;
        this.messages = messages;
        settings(settings);
    }

    @Override
    public void settings(ModerationSettings fresh) {
        // Nothing here is configurable yet.
    }

    public boolean has(Player player) {
        return player.hasPermission(VaultCommand.USE);
    }

    public Stash of(UUID owner) {
        return open.computeIfAbsent(owner, id -> storage.load(id, CAPACITY));
    }

    /**
     * Sneak-right-click with the Banhammer: into the vault if there is room, otherwise it stays in hand.
     *
     * @return whether this was the gesture at all, so the listener knows to swallow the click
     */
    public boolean stashHeld(Player player, boolean sneaking, boolean mainHand) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!banhammer.stashes(sneaking, mainHand, held.getType(), plainName(held), has(player))) {
            return false;
        }
        int accepted = deposit(player, held);
        held.setAmount(held.getAmount() - accepted);
        player.getInventory().setItemInMainHand(held.getAmount() <= 0 ? null : held);
        messages.send(player, accepted > 0 ? "moderation.vault.stashed" : "moderation.vault.no-room-for-hammer");
        return true;
    }

    /**
     * Sneak-right-click with an empty hand: the first Banhammer in the vault comes out into it.
     *
     * @return whether a hammer was drawn, so the listener knows to swallow the click
     */
    public boolean drawHammer(Player player, boolean sneaking, boolean mainHand) {
        boolean handEmpty = player.getInventory().getItemInMainHand().isEmpty();
        // Cheap checks first: the vault is only loaded for somebody who could be drawing.
        if (!banhammer.draws(sneaking, mainHand, handEmpty, has(player), true)) {
            return false;
        }
        Stash vault = of(player.getUniqueId());
        if (!banhammer.draws(sneaking, mainHand, handEmpty, true, vault.contains(this::isHammer))) {
            return false;
        }
        ItemStack hammer = vault.takeFirst(this::isHammer);
        if (hammer == null) {
            return false;
        }
        player.getInventory().setItemInMainHand(hammer);
        save(player.getUniqueId());
        messages.send(player, "moderation.vault.drawn");
        return true;
    }

    private boolean isHammer(ItemStack item) {
        return banhammer.isBanhammer(item.getType(), plainName(item));
    }

    /** @return how many of this went in; the caller takes exactly that many away */
    public int deposit(Player owner, ItemStack item) {
        int accepted = of(owner.getUniqueId()).deposit(item);
        if (accepted > 0) {
            save(owner.getUniqueId());
        }
        return accepted;
    }

    /** Takes one entry out into the owner's inventory; what does not fit stays in the vault. */
    public void take(Player owner, int index, ItemStack shown) {
        Stash vault = of(owner.getUniqueId());
        ItemStack taken = vault.take(index, shown);
        if (taken == null) {
            return;
        }
        if (!hand(owner, vault, taken)) {
            messages.send(owner, "moderation.vault.inventory-full");
        }
        save(owner.getUniqueId());
    }

    /** Takes every entry from {@code from} (inclusive) to {@code to} (exclusive) that fits. */
    public void takeAll(Player owner, int from, int to) {
        Stash vault = of(owner.getUniqueId());
        List<ItemStack> shown = vault.items();
        int moved = 0;
        boolean full = false;
        // Backwards, so taking one does not shift the index of the next.
        for (int index = Math.min(to, shown.size()) - 1; index >= Math.max(0, from); index--) {
            ItemStack taken = vault.take(index, shown.get(index));
            if (taken == null) {
                continue;
            }
            if (hand(owner, vault, taken)) {
                moved++;
            } else {
                full = true;
            }
        }
        if (moved > 0 || full) {
            save(owner.getUniqueId());
        }
        messages.send(owner, full ? "moderation.vault.took-some" : "moderation.vault.took-all",
                "count", String.valueOf(moved));
    }

    public void takeArmour(Player owner, ArmourPiece piece) {
        Stash vault = of(owner.getUniqueId());
        ItemStack taken = vault.takeArmour(piece);
        if (taken == null) {
            return;
        }
        if (!hand(owner, vault, taken)) {
            messages.send(owner, "moderation.vault.inventory-full");
        }
        save(owner.getUniqueId());
    }

    /**
     * Puts the cursor's item on a stand, handing whatever was on it to the cursor.
     *
     * @return what the cursor holds now
     */
    public ItemStack swapOnStand(Player owner, ArmourPiece piece, ItemStack cursor) {
        Stash vault = of(owner.getUniqueId());
        if (cursor != null && !cursor.isEmpty() && ArmourPiece.of(cursor.getType()).orElse(null) != piece) {
            messages.send(owner, "moderation.vault.not-that-piece", "piece", piece.label().toLowerCase());
            return cursor;
        }
        if (cursor != null && cursor.getAmount() > 1) {
            return cursor;
        }
        ItemStack before = vault.swapArmour(piece, cursor);
        save(owner.getUniqueId());
        return before;
    }

    /**
     * Puts on everything on the stands. What was worn in those slots goes onto the stand in its place,
     * so pressing it twice changes back.
     */
    public void equipAll(Player owner) {
        Stash vault = of(owner.getUniqueId());
        EntityEquipment worn = owner.getEquipment();
        int changed = 0;
        for (ArmourPiece piece : ArmourPiece.values()) {
            if (vault.armour(piece).isEmpty()) {
                continue;
            }
            ItemStack current = worn.getItem(piece.slot());
            if (stuck(owner, current)) {
                messages.send(owner, "moderation.vault.cursed", "piece", piece.label().toLowerCase());
                continue;
            }
            ItemStack fromStand = vault.takeArmour(piece);
            if (!current.isEmpty()) {
                if (ArmourPiece.of(current.getType()).orElse(null) == piece) {
                    vault.swapArmour(piece, current);
                } else {
                    // A pumpkin or a head: it cannot go on a stand, so into the vault or the inventory.
                    int accepted = vault.deposit(current);
                    current.setAmount(current.getAmount() - accepted);
                    if (!current.isEmpty()) {
                        hand(owner, vault, current);
                    }
                }
            }
            worn.setItem(piece.slot(), fromStand);
            changed++;
        }
        if (changed > 0) {
            save(owner.getUniqueId());
        }
        messages.send(owner, "moderation.vault.equipped", "count", String.valueOf(changed));
    }

    /**
     * Everything carried in at once — hotbar, storage and off hand, and with {@code alsoWorn} the armour too.
     * What does not fit stays where it was.
     */
    public void depositAll(Player owner, boolean alsoWorn) {
        Stash vault = of(owner.getUniqueId());
        org.bukkit.inventory.PlayerInventory inventory = owner.getInventory();
        int stored = 0;
        boolean full = false;
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            slots.add(slot);
        }
        slots.add(40);   // the off hand
        for (int slot : slots) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int accepted = vault.deposit(stack);
            if (accepted < stack.getAmount()) {
                full = true;
            }
            if (accepted <= 0) {
                continue;
            }
            stack.setAmount(stack.getAmount() - accepted);
            inventory.setItem(slot, stack.isEmpty() ? null : stack);
            stored++;
        }
        if (stored > 0) {
            save(owner.getUniqueId());
        }
        messages.send(owner, full ? "moderation.vault.stored-some" : "moderation.vault.stored-all",
                "count", String.valueOf(stored));
        if (alsoWorn) {
            storeWorn(owner);
        }
    }

    /** Takes off what is worn: onto its empty stand, otherwise into the vault, otherwise it stays on. */
    public void storeWorn(Player owner) {
        Stash vault = of(owner.getUniqueId());
        EntityEquipment worn = owner.getEquipment();
        int stored = 0;
        for (ArmourPiece piece : ArmourPiece.values()) {
            ItemStack current = worn.getItem(piece.slot());
            if (current.isEmpty()) {
                continue;
            }
            if (stuck(owner, current)) {
                messages.send(owner, "moderation.vault.cursed", "piece", piece.label().toLowerCase());
                continue;
            }
            int accepted = vault.deposit(current);
            if (accepted <= 0) {
                continue;
            }
            current.setAmount(current.getAmount() - accepted);
            worn.setItem(piece.slot(), current.isEmpty() ? null : current);
            stored++;
        }
        if (stored > 0) {
            save(owner.getUniqueId());
        }
        messages.send(owner, stored > 0 ? "moderation.vault.stored-worn" : "moderation.vault.no-room",
                "count", String.valueOf(stored));
    }

    public boolean wearsAnything(Player owner) {
        for (ArmourPiece piece : ArmourPiece.values()) {
            if (!owner.getEquipment().getItem(piece.slot()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Writes every vault now, on this thread — for the shutdown, which has no later. */
    public boolean flushNow() {
        boolean all = true;
        for (Map.Entry<UUID, Stash> vault : open.entrySet()) {
            all &= storage.save(vault.getKey(), vault.getValue().contents());
        }
        return all;
    }

    private void save(UUID owner) {
        Stash vault = open.get(owner);
        if (vault != null) {
            Scheduling.async(plugin, () -> storage.save(owner, vault.contents()));
        }
    }

    /**
     * Into the owner's inventory; anything that does not fit goes back into the vault.
     *
     * @return whether all of it fitted
     */
    private static boolean hand(Player owner, Stash vault, ItemStack item) {
        PlayerInventory inventory = owner.getInventory();
        Map<Integer, ItemStack> leftOver = inventory.addItem(item);
        for (ItemStack spare : leftOver.values()) {
            int back = vault.deposit(spare);
            if (back < spare.getAmount()) {
                // Only reachable if the vault filled up in between; the floor beats losing it.
                owner.getWorld().dropItemNaturally(owner.getLocation(), spare.asQuantity(spare.getAmount() - back));
            }
        }
        return leftOver.isEmpty();
    }

    /** Curse of binding keeps a piece on outside creative, here as everywhere else. */
    private static boolean stuck(Player owner, ItemStack worn) {
        return !worn.isEmpty() && owner.getGameMode() != GameMode.CREATIVE
                && worn.containsEnchantment(Enchantment.BINDING_CURSE);
    }

    private static String plainName(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasCustomName()) {
            return null;
        }
        return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().customName());
    }

    @Override
    public String describe() {
        return "operators' personal vaults, and putting the Banhammer away";
    }
}
