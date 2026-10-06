package de.raindancer.modules.moderation.listener;

import de.raindancer.modules.moderation.ModerationServices;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.UUID;

/** Hands every player-on-player kill to the Banhammer. Decides nothing itself. */
public final class BanhammerListener implements IModerationListener {

    private final ModerationServices services;

    public BanhammerListener(ModerationServices services) {
        this.services = services;
    }

    // MONITOR: the death has happened and nothing will un-happen it; a ban on a cancelled death would
    // be a ban for a kill that never was.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        if (killer == null) {
            return;
        }
        boolean swungByKiller = event.getDamageSource().getDirectEntity() == killer;
        services.banhammer().struck(killer, victim, killer.getInventory().getItemInMainHand(), swungByKiller);
    }

    @Override
    public void forget(UUID player) {
        // Remembers nobody.
    }
}
