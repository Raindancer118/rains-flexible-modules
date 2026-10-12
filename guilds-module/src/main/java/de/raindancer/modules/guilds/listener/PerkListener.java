package de.raindancer.modules.guilds.listener;

import de.raindancer.modules.guilds.GuildsServices;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerExpChangeEvent;

/** The Scholar perk: more of the vanilla experience a member picks up. Late, so other plugins' changes come first. */
public final class PerkListener implements IGuildsListener {

    private final GuildsServices services;

    public PerkListener(GuildsServices services) {
        this.services = services;
    }

    @Override
    public void forget(java.util.UUID player) {
        // Nothing is remembered per player here.
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onExperience(PlayerExpChangeEvent event) {
        event.setAmount(services.perks().vanillaXp(event.getPlayer().getUniqueId(), event.getAmount()));
    }
}
