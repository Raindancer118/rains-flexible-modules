package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * The ruleset half of explosions: which of the things a racer can set off are allowed to go off, in
 * which of the run's three dimensions. See {@link SpeedrunExplosive} for the matrix and why it is one.
 *
 * <h2>How this differs from {@link SpeedrunLobbySafetyListener}</h2>
 * That one is about the <em>lobby</em> — everything is refused there, whatever it is, until a run
 * starts, so nobody standing frozen at a start line is blown up. This one is about the <em>race</em>:
 * it applies whatever the lobby is doing, in all three of the run's worlds, and it is per explosive
 * and per dimension because it exists to express a ruleset rather than to keep people safe.
 *
 * <h2>Why a bed is stopped at the click and TNT at the fuse</h2>
 * A bed or an anchor only ever explodes because a player clicked it, and cancelling that click is the
 * one way to stop it that leaves the block itself standing — there is no "bed explosion" event that
 * still knows it was a bed by the time it fires, because the block is gone by then. TNT, a minecart
 * and a crystal go off on their own once lit, hit or pushed, so the fuse is where they are caught;
 * the blast is cancelled too, for anything that never fired a prime event.
 *
 * <h2>Why beds are recognised by name rather than by {@code Tag.BEDS}</h2>
 * {@code Tag.BEDS} resolves against a running server's registry, so touching it at all needs one —
 * which would make every test here an integration test for the sake of one lookup that
 * {@code _BED} answers exactly.
 */
public final class SpeedrunExplosivesListener implements Listener {

    private final SpeedrunLobby lobby;
    private final Messages messages;

    public SpeedrunExplosivesListener(SpeedrunLobby lobby, Messages messages) {
        this.lobby = lobby;
        this.messages = messages;
    }

    /** A bed or a respawn anchor: refused at the click, before anything is primed. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        SpeedrunExplosive kind = kindOf(block.getType());
        if (kind == null || allowed(kind, block.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking() && event.getItem() != null) {
            return;   // building against the block, not using it — vanilla would place, not explode
        }
        event.setCancelled(true);
        if (messages != null) {
            messages.send(player, "speedrun.explosives.refused");
        }
    }

    /** TNT, a TNT minecart or an end crystal: refused at the fuse, so nothing is left to explode. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        SpeedrunExplosive kind = kindOf(event.getEntity());
        if (kind != null && !allowed(kind, event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    /** The blast itself, for whatever reaches it without a fuse event of its own. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Entity source = event.getEntity();
        if (source == null) {
            return;
        }
        SpeedrunExplosive kind = kindOf(source);
        if (kind != null && !allowed(kind, source.getWorld())) {
            event.setCancelled(true);
        }
    }

    /** Whether {@code kind} may go off in {@code world} — always yes outside the run's own three. */
    private boolean allowed(SpeedrunExplosive kind, World world) {
        if (world == null) {
            return true;
        }
        SpeedrunSettings config = lobby.config();
        if (!SpeedrunWorlds.around(config.worldName()).contains(world.getName())) {
            return true;
        }
        return kind.allowedIn(config, world.getEnvironment());
    }

    private static SpeedrunExplosive kindOf(Material material) {
        if (material == null) {
            return null;
        }
        if (material == Material.RESPAWN_ANCHOR) {
            return SpeedrunExplosive.RESPAWN_ANCHOR;
        }
        return material.name().endsWith("_BED") ? SpeedrunExplosive.BED : null;
    }

    private static SpeedrunExplosive kindOf(Entity entity) {
        if (entity instanceof TNTPrimed || entity instanceof ExplosiveMinecart) {
            return SpeedrunExplosive.TNT;
        }
        // A creeper is the hazard feature's, never this one's — see the class javadoc.
        return entity instanceof EnderCrystal ? SpeedrunExplosive.END_CRYSTAL : null;
    }
}
