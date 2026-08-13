package de.raindancer.modules.mannequin.listener;

import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.service.MannequinService;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * A mannequin can now genuinely die — see {@code MannequinCombatListener}'s javadoc. What this
 * listener guarantees in exchange: nothing obtainable is ever produced by killing one, and the
 * training room never has a permanent gap where a dummy used to stand.
 */
public final class MannequinDeathListener implements IMannequinListener {

    private final MannequinRegistry registry;
    private final MannequinService mannequins;

    public MannequinDeathListener(MannequinRegistry registry, MannequinService mannequins) {
        this.registry = registry;
        this.mannequins = mannequins;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Optional<String> id = registry.idFor(event.getEntity().getUniqueId());
        if (id.isEmpty()) {
            return;
        }

        // Unconditional, regardless of what it was wearing or how it died: a mannequin's loadout is
        // structurally unobtainable, and a death is not an exception to that.
        event.getDrops().clear();
        event.setDroppedExp(0);

        Optional<Mannequin> mannequin = registry.get(id.get());
        if (mannequin.isPresent()) {
            mannequins.scheduleRespawn(mannequin.get());
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing per-player here: a death is between the mannequin and whatever service brings it back.
    }

    @Override
    public String describe() {
        return "clearing a killed mannequin's drops and scheduling its identical respawn";
    }
}
