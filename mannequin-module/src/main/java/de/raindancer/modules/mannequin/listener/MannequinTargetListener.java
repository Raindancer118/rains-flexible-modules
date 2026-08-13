package de.raindancer.modules.mannequin.listener;

import de.raindancer.modules.mannequin.service.MannequinService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityTargetEvent;

import java.util.UUID;

/**
 * Stops any hostile mob from ever picking a tracked mannequin as a target in the first place.
 *
 * <h2>Why disabling the mannequin's own AI is not enough</h2>
 * {@code MannequinService#disableAiAndFalling} turns off a mob-kind mannequin's own AI so it never
 * acts — but whether something else <em>attacks it</em> is a decision made entirely by the
 * attacker's own AI, independent of the mannequin's. A vanilla Zombie's target-selector goal picks
 * a nearby Iron Golem or Villager on sight regardless of whether that Golem can fight back, which
 * is exactly why an Iron Golem mannequin — AI disabled, unable to defend itself — was getting
 * attacked by ordinary wandering hostiles. Cancelling {@link EntityTargetEvent} whenever the
 * chosen target is a tracked mannequin stops the attacker from ever swinging at it, for any
 * mannequin kind, rather than only reacting to damage that has already landed.
 */
public final class MannequinTargetListener implements IMannequinListener {

    private final MannequinService mannequins;

    public MannequinTargetListener(MannequinService mannequins) {
        this.mannequins = mannequins;
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() == null) {
            return;
        }
        if (mannequins.isTracked(event.getTarget().getUniqueId())) {
            event.setTarget(null);
            event.setCancelled(true);
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing per-player here: targeting is between the attacker and the mannequin.
    }

    @Override
    public String describe() {
        return "stopping any hostile mob from targeting a tracked mannequin in the first place";
    }
}
