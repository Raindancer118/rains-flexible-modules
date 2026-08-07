package de.raindancer.modules.hungergames.listener;

import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.ProjectileHitEvent;

import java.util.UUID;

/**
 * A thrown bottle of krückauwasser landing.
 *
 * <h2>Why the bottle is a real projectile at all</h2>
 * The port had this item hitscan to whatever the thrower was looking at, and reasoned that where the splash
 * lands is the only thing a real throw adds. That is true, and it is the item: a thrown bottle arcs, is
 * dodged by moving, is stopped by the wall somebody is standing behind, and lands at your own feet if you
 * aim down. "Get out of the way" is the entire counterplay this item is balanced around, and a hitscan
 * version has none. The source threw a snowball wearing a splash potion and waited for this event; so does
 * this.
 *
 * <h2>Why the effect is behind a seam</h2>
 * Same reason as everywhere else here: what a splash does — how far, how long, to whom — belongs to
 * {@code CombatItemService} and the settings, and this class must be nothing but "a thing landed, and it
 * was one of ours".
 */
public final class KrueckauwasserListener implements IHungerGamesListener {

    /** What a landing bottle does. */
    @FunctionalInterface
    public interface Impact {

        /**
         * @return whether this projectile was one of ours — {@code false} leaves an ordinary snowball
         *         entirely alone, which matters because a tribute may simply be throwing snowballs
         */
        boolean landed(Projectile projectile, String worldName, double x, double y, double z);
    }

    private final Impact impact;

    public KrueckauwasserListener(Impact impact) {
        this.impact = impact;
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        var where = projectile.getLocation();
        if (where.getWorld() == null) {
            return;
        }
        if (impact.landed(projectile, where.getWorld().getName(), where.getX(), where.getY(), where.getZ())) {
            // Removed only when it was ours. A snowball is removed by the server anyway; doing it here for
            // one that is not ours would be this module deleting another plugin's projectile.
            projectile.remove();
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered per player. What a bottle does is written on the bottle, so a thrower who
        // logs out mid-flight leaves nothing behind here.
    }

    @Override
    public String describe() {
        return "a thrown bottle of krückauwasser landing";
    }
}
