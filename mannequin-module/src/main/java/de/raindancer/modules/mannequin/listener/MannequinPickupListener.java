package de.raindancer.modules.mannequin.listener;

import de.raindancer.modules.mannequin.service.MannequinPotionService;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityPickupItemEvent;

import java.util.UUID;

/**
 * A potion, golden apple or enchanted golden apple dropped at a mannequin's feet is consumed, not
 * carried — for whichever mannequins this fires on. See {@link MannequinPotionService#tryConsume}
 * for why a periodic sweep in {@code MannequinModule} exists alongside this listener rather than
 * relying on it alone.
 *
 * <h2>Composes with mortality, not invincibility</h2>
 * {@link MannequinCombatListener} lets ordinary damage through up to a mannequin's configured max
 * health, so a Harming potion's instant-damage effect genuinely hurts it like any other source —
 * that is consistent with everything else in this module, not a special case.
 */
public final class MannequinPickupListener implements IMannequinListener {

    private final MannequinRegistry registry;
    private final MannequinPotionService potions;

    public MannequinPickupListener(MannequinRegistry registry, MannequinPotionService potions) {
        this.registry = registry;
        this.potions = potions;
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (registry.idFor(event.getEntity().getUniqueId()).isEmpty()) {
            return;
        }
        if (!(event.getEntity() instanceof org.bukkit.entity.Mannequin mannequin)) {
            return;
        }
        if (!MannequinPotionService.isRecognised(event.getItem().getItemStack().getType())) {
            return;
        }
        event.setCancelled(true);
        potions.tryConsume(mannequin, event.getItem());
    }

    @Override
    public void forget(UUID player) {
        // Nothing per-player here either — a pickup is between the mannequin and the item entity.
    }

    @Override
    public String describe() {
        return "a mannequin consuming a potion or golden apple dropped at its feet, rather than "
                + "carrying it";
    }
}
