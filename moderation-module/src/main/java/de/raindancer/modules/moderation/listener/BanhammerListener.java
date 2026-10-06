package de.raindancer.modules.moderation.listener;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.rules.BanhammerRule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.UUID;

/** Hands every player-on-player hit to the Banhammer. Decides nothing itself. */
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

    @Override
    public void forget(UUID player) {
        // Remembers nobody.
    }
}
