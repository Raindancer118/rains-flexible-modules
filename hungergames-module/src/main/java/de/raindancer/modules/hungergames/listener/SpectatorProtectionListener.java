package de.raindancer.modules.hungergames.listener;

import de.raindancer.modules.hungergames.service.SpectatorService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.UUID;

/**
 * What being a spectator actually means, for anybody this module put into that vanish-based state
 * rather than vanilla's own: no breaking, no placing, no item but the compass, no damage given or
 * taken, no hunger, and — for an eliminated tribute specifically — a respawn that puts them back where
 * they fell rather than at the world's spawn point.
 *
 * <h2>Why this is not on {@link SpectatorService} itself</h2>
 * That class is what a spectator <em>is</em> — vanished, flying, holding a compass — and is reached
 * from several unrelated places: a real death, a rejoin, an admin's correction, a gamemaster's own
 * choice. This is what a spectator <em>may not do</em>, and it is one thing, asked on every event
 * Bukkit has an opinion about. Folding the two together would make {@code SpectatorService} a listener
 * that also happens to hold state, which is the shape {@code EliminationListener}'s own class note
 * warns against.
 *
 * <h2>Why the check is {@link SpectatorService#isVanishSpectator}, not "eliminated tribute"</h2>
 * A gamemaster who picked "Watch without being seen" needs exactly these guarantees and is not a
 * tribute at all — {@code session.participants()} would never have known about them. {@code Vanish}
 * itself is shared more widely still, with ordinary staff vanish, and a moderator hidden to watch a
 * build must keep every one of these abilities — hiding is the only thing that case has in common with
 * this one. {@link SpectatorService#isVanishSpectator} is the one flag that is true for exactly the two
 * cases this listener exists for and nothing else.
 */
public final class SpectatorProtectionListener implements IHungerGamesListener {

    private final SpectatorService spectators;

    public SpectatorProtectionListener(SpectatorService spectators) {
        this.spectators = spectators;
    }

    /**
     * Keeps a respawn from moving an eliminated tribute to the world's spawn point.
     *
     * <p>They died for real — a spectator who stayed in survival is a spectator who can still take a
     * killing blow before {@link SpectatorService#makeSpectator} ever runs — and Bukkit fires this
     * event on its own schedule afterwards, with vanilla's own idea of where to put them. Overriding
     * it is the "teleported to where they last were" half of being a spectator; the flying, the
     * vanish and the compass are the other half, already done by the time this fires.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        spectators.lastKnownLocation(event.getPlayer().getUniqueId()).ifPresent(event::setRespawnLocation);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (spectators.isVanishSpectator(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (spectators.isVanishSpectator(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * No item but the compass — and the compass is exempted rather than skipped by priority. Core's
     * own listener answers a custom item's right-click through this same event, and cancelling it
     * here first would take the ability with it, whatever order the two end up registered in.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!spectators.isVanishSpectator(player.getUniqueId())) {
            return;
        }
        if (spectators.isTheSpectatorCompass(event.getItem())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (spectators.isVanishSpectator(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** No damage taken — whatever the cause, not only another player's. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player victim && spectators.isVanishSpectator(victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** No damage dealt — a melee hit, or an arrow they loosed before anybody noticed they should not have. */
    @EventHandler(ignoreCancelled = true)
    public void onDamageDealt(EntityDamageByEntityEvent event) {
        UUID attacker = attackerOf(event.getDamager());
        if (attacker != null && spectators.isVanishSpectator(attacker)) {
            event.setCancelled(true);
        }
    }

    private UUID attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player.getUniqueId();
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter.getUniqueId();
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && spectators.isVanishSpectator(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing is cached here — every check reads SpectatorService fresh, which is what makes this
        // listener correct the instant somebody enters or leaves the vanish-spectator state rather than
        // on their next join.
    }

    @Override
    public String describe() {
        return "what a vanish-based spectator may not do: break, place, use, hurt, be hurt, or go hungry";
    }
}
