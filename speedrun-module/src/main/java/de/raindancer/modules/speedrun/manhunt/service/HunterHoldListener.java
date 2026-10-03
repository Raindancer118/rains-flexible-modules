package de.raindancer.modules.speedrun.manhunt.service;

import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The Runners' head start ({@code ManhuntSettings.hunterHeadStartSeconds}): until {@link #release},
 * every Hunter stands still and touches nothing — no step, no block, no item, no hit. Registered per
 * run through {@code SpeedrunRun.listen}, so it goes when the run does even if the release never comes.
 *
 * <p>Who is a Hunter is asked of the hunt on every event rather than copied, so a Runner turning
 * Hunter mid head start is held too — the same "re-derive, never cache" rule as the rest of the module.
 * Brought back from before the 0.11 rebuild, where the hold was only a movement freeze.
 *
 * <p>The same hold also keeps one Hunter still on their own for a while ({@link #holdFor}) — the
 * respawn wait — which is why it is registered for every run, head start or not.
 */
public final class HunterHoldListener implements Listener {

    private final Hunt hunt;
    private final LongSupplier clockMillis;
    private final java.util.Map<UUID, Long> heldUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean released;

    public HunterHoldListener(Hunt hunt) {
        this(hunt, System::currentTimeMillis);
    }

    public HunterHoldListener(Hunt hunt, LongSupplier clockMillis) {
        this.hunt = Objects.requireNonNull(hunt, "hunt");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** Keeps {@code player} still on their own for {@code seconds}, whatever the head start does. */
    public void holdFor(UUID player, int seconds) {
        if (seconds > 0) {
            heldUntil.put(player, clockMillis.getAsLong() + seconds * 1000L);
        }
    }

    /** Whether {@code player} is held right now, by the head start or on their own. */
    public boolean isHeld(UUID player) {
        return held(player);
    }

    /** How long {@code player}'s own hold has to go, rounded up — 0 when they are free. */
    public int secondsLeft(UUID player) {
        Long until = heldUntil.get(player);
        long left = until == null ? 0 : until - clockMillis.getAsLong();
        return left <= 0 ? 0 : (int) ((left + 999) / 1000);
    }

    /** The head start is over. */
    public void release() {
        released = true;
    }

    public boolean isHolding() {
        return !released;
    }

    private boolean held(UUID player) {
        if (player == null) {
            return false;
        }
        if (!released && hunt.isHunter(player)) {
            return true;
        }
        Long until = heldUntil.get(player);
        if (until == null) {
            return false;
        }
        if (until > clockMillis.getAsLong()) {
            return true;
        }
        heldUntil.remove(player, until);
        return false;
    }

    /** An actual step, not a look around, at {@code HIGHEST} — the last priority that can still refuse it. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!held(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (held(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (held(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // Two handlers, not one on the abstract PlayerBucketEvent, which Bukkit cannot register.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        onBucket(event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        onBucket(event);
    }

    private void onBucket(PlayerBucketEvent event) {
        if (held(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Blocks and items alike — a pearl, a bow, a door. {@code HIGH}, after the tracking compass has
     * already answered its own right-click at {@code NORMAL}, so a held Hunter can still aim it.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (held(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        UUID attacker = null;
        if (event.getDamager() instanceof Player player) {
            attacker = player.getUniqueId();
        } else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter.getUniqueId();
        }
        if (held(attacker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (held(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private static boolean sameBlock(Location from, Location to) {
        return from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }
}
