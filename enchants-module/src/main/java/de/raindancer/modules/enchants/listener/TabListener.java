package de.raindancer.modules.enchants.listener;

import de.raindancer.modules.enchants.EnchantAdvancements;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.UUID;

/** Counts an enchantment's first use toward the full set even when another module awarded it (Auto Sell). */
public final class TabListener implements IEnchantsListener {

    private final EnchantAdvancements advancements;

    public TabListener(EnchantAdvancements advancements) {
        this.advancements = advancements;
    }

    @EventHandler
    public void onDone(PlayerAdvancementDoneEvent event) {
        if (EnchantAdvancements.isFirstUse(event.getAdvancement().getKey().toString())) {
            advancements.recount(event.getPlayer().getUniqueId());
        }
    }

    @Override
    public void forget(UUID player) {
        advancements.forget(player);
    }
}
