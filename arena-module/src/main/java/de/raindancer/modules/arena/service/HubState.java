package de.raindancer.modules.arena.service;

import de.raindancer.modules.arena.ArenaSettings;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

/**
 * What everybody is in the hub: empty hands, full health, nothing active. This server's inventories are its own — what
 * somebody owns lives on the survival server and never comes here — so emptying them takes nothing from anybody.
 */
public final class HubState {

    private HubState() {
    }

    /** On the player's own thread. */
    public static void apply(Player player, ArenaSettings settings) {
        player.closeInventory();
        player.getInventory().clear();
        player.getEnderChest().clear();
        player.setItemOnCursor(null);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.setGameMode(settings.gameModeOrDefault());
        player.setAllowFlight(false);
        player.setFlying(false);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setLevel(0);
        player.setExp(0f);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0);
        player.setArrowsInBody(0);
        player.setBeeStingersInBody(0);
    }
}
