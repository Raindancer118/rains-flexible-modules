package de.raindancer.modules.anticheat.listener;

import de.raindancer.modules.anticheat.AntiCheatServices;
import de.raindancer.modules.anticheat.model.Flag;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/** Melee attacks, critical hits and, without the packet tap, swings. */
public final class CombatListener implements IAntiCheatListener {

    private final AntiCheatServices services;

    public CombatListener(AntiCheatServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (!event.willAttack()) {
            return;
        }
        if (services.combat().attack(event.getPlayer(), event.getAttacked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        if (event.isCritical() && services.combat().critical(attacker)) {
            event.setDamage(event.getDamage() / 1.5);
        }
        double keep = services.combat().dampening(attacker);
        if (keep < 1.0) {
            event.setDamage(event.getDamage() * keep);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent event) {
        PlayerTrack track = services.tracks().of(event.getPlayer());
        if (track.packets.tapped) {
            return;
        }
        Flag clicked = null;
        track.combat.unswung.pollFirst();
        long now = track.now();
        if (!track.world.digging() && now - track.combat.lastUseMillis > 60) {
            clicked = services.clicks().click(track, now);
        }
        if (clicked != null) {
            services.violations().flag(event.getPlayer(), track, clicked);
        }
    }

    @Override
    public String describe() {
        return "judging attacks and critical hits as they happen";
    }
}
