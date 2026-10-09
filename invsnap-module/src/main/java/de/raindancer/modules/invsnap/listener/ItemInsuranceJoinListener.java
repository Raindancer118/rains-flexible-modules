package de.raindancer.modules.invsnap.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.invsnap.model.ItemPolicy;
import de.raindancer.modules.invsnap.service.ItemInsuranceService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/** Where an owner is told what happened while away, and handed what is waiting for them. */
public final class ItemInsuranceJoinListener implements IInvSnapListener {

    private final Plugin plugin;
    private final ItemInsuranceService insurance;
    private final Messages messages;

    public ItemInsuranceJoinListener(Plugin plugin, ItemInsuranceService insurance, Messages messages) {
        this.plugin = plugin;
        this.insurance = insurance;
        this.messages = messages;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        announce(event.getPlayer());
    }

    /** One tick on: the dead player's inventory is theirs to fill only once they are back in the world. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Scheduling.entityLater(plugin, player, 2L, () -> announce(player));
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            insurance.sweep(player);
        }
    }

    /** Strips dead marks, tells of ended policies, and hands over what is waiting. */
    public void announce(Player player) {
        insurance.sweep(player);
        for (ItemPolicy ended : insurance.takeNotices(player.getUniqueId())) {
            if (ItemPolicy.WORN.equals(ended.ended())) {
                messages.send(player, "invsnap.item.worn", "item", ended.description());
            } else {
                messages.send(player, "invsnap.item.lapsed", "item", ended.description(),
                        "price", Fees.format(Fees.quote(ItemInsuranceService.SOURCE,
                                insurance.premiumOf(ended))));
            }
        }
        int before = insurance.pendingCount(player.getUniqueId());
        if (before == 0) {
            return;
        }
        int handed = insurance.deliver(player);
        if (handed > 0) {
            messages.send(player, "invsnap.item.delivered", "count", String.valueOf(handed));
        }
        int left = before - handed;
        if (left > 0) {
            messages.send(player, "invsnap.item.waiting", "count", String.valueOf(left));
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing remembered.
    }

    @Override
    public String describe() {
        return "item insurance: lapsed-policy notices and the to-collect list, on join and respawn";
    }
}
