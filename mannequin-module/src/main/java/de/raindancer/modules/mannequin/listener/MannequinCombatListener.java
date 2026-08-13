package de.raindancer.modules.mannequin.listener;

import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.service.MannequinCombatService;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * The combat tracking, stats, durability and redstone feedback for a hit landing on a tracked
 * mannequin.
 *
 * <h2>A mannequin is not invulnerable</h2>
 * This listener deliberately does <em>not</em> cancel {@link EntityDamageEvent} any more — a
 * mannequin has a real health pool and a hit that reaches it damages it exactly like any other
 * living entity, up to and including killing it. {@code MannequinDeathListener} is what guards the
 * one thing that still has to be guaranteed: nothing obtainable is ever left behind.
 *
 * <h2>Why {@link EntityDamageByEntityEvent} rather than the superclass here</h2>
 * Stats, durability, the redstone pulse and the "would have killed a player" feedback are all
 * things only a player's own hit produces — fire, the void or a firework do not owe anybody a
 * combo counter. Listening on the subclass and reading the damage before anything else happens is
 * enough; there is no cancellation left to order around.
 */
public final class MannequinCombatListener implements IMannequinListener {

    private final MannequinRegistry registry;
    private final MannequinCombatService combat;

    public MannequinCombatListener(MannequinRegistry registry, MannequinCombatService combat) {
        this.registry = registry;
        this.combat = combat;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Optional<String> id = registry.idFor(event.getEntity().getUniqueId());
        if (id.isEmpty()) {
            return;
        }
        Optional<Mannequin> mannequin = registry.get(id.get());
        if (mannequin.isEmpty() || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity live)) {
            return;
        }
        combat.recordHit(mannequin.get(), live, attacker, event.getFinalDamage(),
                System.currentTimeMillis());
    }

    @Override
    public void forget(UUID player) {
        // Nothing remembered per-player here: the training tally lives against the mannequin's own
        // id in MannequinRegistry, not against whoever last hit it.
    }

    @Override
    public String describe() {
        return "tracking combo, durability and redstone feedback on a player's hits against mannequins";
    }
}
