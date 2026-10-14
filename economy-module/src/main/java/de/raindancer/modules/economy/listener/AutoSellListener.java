package de.raindancer.modules.economy.listener;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.modules.economy.EconomyServices;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Rain's Auto Sell enchantment, registered by the enchants module and enforced here, where the prices are.
 * At NORMAL: after Smelting has turned the drops (LOW), before Chest Link and Telekinesis take the rest
 * (HIGH, HIGHEST). A player's own death never sells anything.
 */
public final class AutoSellListener implements IEconomyListener {

    private static final String ROOT = "rainsenchants:root";

    private final EconomyServices services;

    public AutoSellListener(EconomyServices services) {
        this.services = services;
    }

    private static boolean autoSells(Player player) {
        return CustomEnchantments.levelOn(player.getInventory().getItemInMainHand(), RainsEnchants.AUTO_SELL) > 0;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        if (event.getItems().isEmpty() || !autoSells(player)) {
            return;
        }
        List<ItemStack> stacks = new ArrayList<>();
        event.getItems().forEach(item -> stacks.add(item.getItemStack()));
        boolean[] sold = services.shop().autoSell(player, stacks);
        int i = 0;
        boolean any = false;
        for (Iterator<Item> each = event.getItems().iterator(); each.hasNext(); i++) {
            each.next();
            if (sold[i]) {
                each.remove();
                any = true;
            }
        }
        if (any) {
            used(player);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (event instanceof PlayerDeathEvent || killer == null || event.getDrops().isEmpty() || !autoSells(killer)) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        boolean[] sold = services.shop().autoSell(killer, drops);
        boolean any = false;
        for (int i = sold.length - 1; i >= 0; i--) {
            if (sold[i]) {
                drops.remove(i);
                any = true;
            }
        }
        if (any) {
            used(killer);
        }
    }

    /** The enchantment's advancement lives in the enchants module's tab; it is granted only if that tab exists. */
    private void used(Player player) {
        var advancements = services.core().advancements();
        if (advancements.isLoaded(RainsEnchants.AUTO_SELL)) {
            advancements.award(player.getUniqueId(), ROOT);
            advancements.award(player.getUniqueId(), RainsEnchants.AUTO_SELL);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        services.shop().payAutoSales(services.plugin(), event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        // The owed money is paid on quit; nothing else is remembered.
    }
}
