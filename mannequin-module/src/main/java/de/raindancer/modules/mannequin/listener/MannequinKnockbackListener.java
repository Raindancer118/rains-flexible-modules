package de.raindancer.modules.mannequin.listener;

import de.raindancer.modules.mannequin.service.MannequinService;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.event.EventHandler;

import java.util.UUID;

/**
 * The other half of "bound to its block, fully static" for a mannequin that is not Paper's own
 * {@code org.bukkit.entity.Mannequin} entity.
 *
 * <h2>Why this exists alongside {@code setGravity(false)}</h2>
 * {@code org.bukkit.entity.Mannequin#setImmovable(true)} is a Mannequin-only API — there is nothing
 * equivalent for a {@code Zombie}, {@code Skeleton}, {@code Wither} or {@code IronGolem}. {@code
 * MannequinService#spawn} already turns gravity off for every one of those kinds, which stops it
 * falling, but a hit still knocks a mob-type entity backwards exactly like any other living entity —
 * {@code setGravity(false)} has nothing to say about knockback at all. Cancelling every knockback
 * for a tracked mannequin, unconditionally, is the other half: harmless and redundant for {@code
 * PLAYER} (already immovable), and the one mechanism that actually makes the other four kinds rigid
 * too.
 *
 * <h2>{@link EntityKnockbackEvent} — the Paper replacement, not the deprecated Bukkit one</h2>
 * {@code org.bukkit.event.entity.EntityKnockbackEvent} is deprecated for removal on this Paper
 * version, and firing a listener against it logs a startup warning that it costs server
 * performance — {@code io.papermc.paper.event.entity.EntityKnockbackEvent} is its replacement,
 * functionally the same event (still fired for every knockback source: a melee hit, an explosion,
 * a piston, a splash potion's push, each with its own {@code getCause()}), without the penalty.
 */
public final class MannequinKnockbackListener implements IMannequinListener {

    private final MannequinService mannequins;

    public MannequinKnockbackListener(MannequinService mannequins) {
        this.mannequins = mannequins;
    }

    @EventHandler(ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (!mannequins.isTracked(event.getEntity().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
    }

    @Override
    public void forget(UUID player) {
        // Nothing per-player here: knockback is between the mannequin and whatever pushed it.
    }

    @Override
    public String describe() {
        return "cancelling all knockback on a tracked mannequin, for kinds that have no setImmovable "
                + "of their own";
    }
}
