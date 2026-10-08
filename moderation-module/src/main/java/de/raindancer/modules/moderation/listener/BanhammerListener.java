package de.raindancer.modules.moderation.listener;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.rules.BanhammerRule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

/**
 * Hands every player-on-player hit to the Banhammer, and every sneaking right click to the vault, which
 * puts the hammer away. Decides nothing itself.
 */
public final class BanhammerListener implements IModerationListener {

    private final ModerationServices services;

    public BanhammerListener(ModerationServices services) {
        this.services = services;
    }

    // Cancelled hits too: in a no-PvP claim the swing still means "ban", and the op meant it. The hit
    // itself is then cancelled — the ban is the point, not the damage.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !(event.getDamager() instanceof Player attacker)) {
            return;
        }
        // The attacker's own swing: dealt by them directly, and a melee damage type — thorns is also
        // booked to the armour's wearer, and must not turn being hit into banning the hitter.
        boolean swung = event.getDamageSource().getDirectEntity() == attacker
                && BanhammerRule.isSwing(event.getDamageSource().getDamageType().getKey().asString());
        if (services.banhammer().struck(attacker, victim, attacker.getInventory().getItemInMainHand(), swung)) {
            event.setCancelled(true);
        }
    }

    // Lowest and regardless of cancellation: a right click into the air arrives already cancelled, and
    // putting the hammer away should beat whatever the block that was clicked would have done.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (stashOrDraw(event.getPlayer(), event.getHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRightClickEntity(PlayerInteractEntityEvent event) {
        if (stashOrDraw(event.getPlayer(), event.getHand())) {
            event.setCancelled(true);
        }
    }

    /** Puts the hammer away when it is held, or draws it when the hand is empty. */
    private boolean stashOrDraw(Player player, EquipmentSlot hand) {
        boolean mainHand = hand == EquipmentSlot.HAND;
        return services.vaults().stashHeld(player, player.isSneaking(), mainHand)
                || services.vaults().drawHammer(player, player.isSneaking(), mainHand);
    }

    @Override
    public void forget(UUID player) {
        // Remembers nobody.
    }
}
