package de.raindancer.modules.claims;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandAction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Optional;

/**
 * Keeps people inside a claim supplied from its equipment stock.
 * <p>
 * Strictly additive: it only ever fills an empty slot, never swaps out or removes what somebody is
 * already carrying, and never hands out anything the stock does not hold. A player who already has the
 * item — anywhere in their inventory — is left alone, so this tops up rather than duplicating.
 */
public final class EquipService {

    /** A snapshot, replaced on reload — see settings(ClaimSettings). */
    private volatile ClaimSettings settings;
    private final Features features;
    private final Land protection;
    private final ClaimService claimService;
    private final Messages messages;
    private final ClaimNames claimNames;

    public EquipService(ClaimSettings settings, Features features, Land protection,
                        ClaimService claimService, Messages messages, ClaimNames claimNames) {
        this.settings = settings;
        this.features = features;
        this.protection = protection;
        this.claimService = claimService;
        this.messages = messages;
        this.claimNames = claimNames;
    }

    /**
     * Swaps in the settings as they are now.
     *
     * <p>Called on reload. The field is a snapshot rather than a live view, so nothing here has to think about a
     * value changing halfway through a calculation — and replacing the whole snapshot means a reload takes effect
     * on the next event rather than on the next restart.
     */
    public void settings(ClaimSettings settings) {
        this.settings = settings;
    }

    /** Runs every rule for this player. Returns how many items were handed out. */
    public int equip(Player player, Claim claim) {
        // One question covers the server policy, the owner's switch and whether they included this
        // player's group.
        if (!features.appliesTo(claim, ClaimFeature.AUTO_EQUIP, player)) {
            return 0;
        }
        ClaimEquipment equipment = claim.equipment();
        if (equipment.rules().isEmpty() || equipment.stockEmpty()) {
            return 0;
        }
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return 0;
        }
        if (!protection.has(claim.area(), player, LandAction.ENTER)) {
            return 0;
        }
        int handedOut = 0;
        for (EquipRule rule : equipment.rules()) {
            handedOut += applyRule(player, claim, equipment, rule);
        }
        if (handedOut > 0) {
            claim.markDirty();
            claimService.saveAsync(claim);
        }
        return handedOut;
    }

    private int applyRule(Player player, Claim claim, ClaimEquipment equipment, EquipRule rule) {
        ItemStack template = rule.template();
        PlayerInventory inventory = player.getInventory();

        int missing = rule.keepAmount() - countCarried(inventory, template);
        if (missing <= 0) {
            return 0;
        }

        EquipRule.Target target = rule.resolvedTarget();
        Optional<EquipmentSlot> equipmentSlot = target.toEquipmentSlot();

        if (equipmentSlot.isPresent()) {
            // Armour and the off hand hold exactly one item, so this is all-or-nothing.
            EquipmentSlot slot = equipmentSlot.get();
            ItemStack current = inventory.getItem(slot);
            if (current != null && !current.getType().isAir()) {
                return 0;
            }
            Optional<ItemStack> taken = equipment.take(template, 1);
            if (taken.isEmpty()) {
                return 0;
            }
            inventory.setItem(slot, taken.get());
            announce(player, claim, taken.get(), rule);
            return 1;
        }

        // Hotbar: only fill the chosen key, and only if it is free or already holds the same item.
        int slot = rule.hotbarSlot();
        ItemStack current = inventory.getItem(slot);
        int room;
        if (current == null || current.getType().isAir()) {
            room = Math.min(missing, template.getMaxStackSize());
        } else if (current.isSimilar(template)) {
            room = Math.min(missing, current.getMaxStackSize() - current.getAmount());
        } else {
            // Somebody's own item is in that slot — leave it be rather than shuffling their hotbar.
            return 0;
        }
        if (room <= 0) {
            return 0;
        }

        Optional<ItemStack> taken = equipment.take(template, room);
        if (taken.isEmpty()) {
            return 0;
        }
        ItemStack handed = taken.get();
        if (current == null || current.getType().isAir()) {
            inventory.setItem(slot, handed);
        } else {
            current.setAmount(current.getAmount() + handed.getAmount());
        }
        announce(player, claim, handed, rule);
        return handed.getAmount();
    }

    /** How many of the item the player already has, anywhere including armour and the off hand. */
    private int countCarried(PlayerInventory inventory, ItemStack template) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.isSimilar(template)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void announce(Player player, Claim claim, ItemStack handed, EquipRule rule) {
        player.sendActionBar(messages.prefixed("equip.given", 
                "amount", String.valueOf(handed.getAmount()),
                "item", handed.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                "where", rule.describeTarget(),
                // The possessive for the same reason the pantry uses it: a gift has a giver.
                "claim", claimNames.possessive(claim)));
    }
}
