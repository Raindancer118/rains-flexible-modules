package de.raindancer.modules.manhunt.tracker;

import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Point;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

/**
 * The tracking compass' moments in a Bukkit event: a Runner going through a portal, a Hunter
 * right-clicking their compass, a Hunter dying and respawning, and either of them leaving.
 *
 * <h2>Why the portal is recorded here rather than in {@link TrackerCompassService}</h2>
 * Same split the rest of this module already has: a listener turns an event into a plain fact — "this Runner left this spot in this world" — and {@link PortalMemory}
 * stores it without ever seeing a Bukkit type. That is what lets the compass be aimed at a door in
 * tests that never load a server.
 *
 * <h2>Registered for the life of one hunt, through {@code SpeedrunRun.listen}</h2>
 * The lobby unregisters it on every path a run can end by, so there is no moment where a crash leaves
 * a listener behind holding a hunt that is over. Every handler still asks the hunt it was built with,
 * because a handler can fire in the tick between a run finishing and the lobby forgetting it.
 */
public final class TrackerListener implements Listener {

    private final Hunt hunt;
    private final TrackerCompassService tracker;
    private final PortalMemory portals;

    public TrackerListener(Hunt hunt, TrackerCompassService tracker, PortalMemory portals) {
        this.hunt = Objects.requireNonNull(hunt, "hunt");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.portals = Objects.requireNonNull(portals, "portals");
    }

    /**
     * A Runner stepping into a Nether or End portal: the spot they left from is remembered against the
     * world they left, so a Hunter still up here is pointed at that door rather than at a needle that
     * cannot follow them down.
     *
     * <p>Deliberately {@code MONITOR}, {@code ignoreCancelled}: a crossing another plugin refuses is
     * not a crossing, and remembering it would send the Hunters to a door the Runner never used.
     * {@link PlayerPortalEvent#getFrom()} rather than the player's live location, because the two can
     * already differ by the time this runs and {@code getFrom} is the side of the portal that is in
     * the world the Hunters are still standing in.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        if (!hunt.isRunner(player.getUniqueId()) || hunt.isEliminated(player.getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        if (from == null || from.getWorld() == null) {
            return;
        }
        portals.remember(player.getUniqueId(),
                new Point(from.getWorld().getName(), from.getX(), from.getY(), from.getZ()));
    }

    /** A Hunter right-clicking the compass follows the next Runner along — see
     *  {@link TrackerCompassService#cycleTarget}, which decides whether that is allowed at all. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!tracker.isTracker(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        if (!hunt.isHunter(player.getUniqueId())) {
            return;
        }
        // The compass is a button here, not a block-placing item: a right-click on a lodestone would
        // otherwise bind it for real and undo the aim on the very next sweep.
        event.setCancelled(true);
        tracker.cycleTarget(player);
    }

    /**
     * A tracking compass never drops from a body.
     *
     * <h2>Why not, and why removed rather than kept</h2>
     * The player most likely to be standing over a dead Hunter is the Runner who just killed them, and
     * a dropped tracking compass is a working tracking compass: picked up, it would show a Runner
     * their own side's position — the hunt handed to the wrong team as a reward for winning a fight.
     *
     * <p>Taken out of the drops rather than kept through death, because keeping it would mean
     * keep-inventory, which is the server's own rule and this module's to borrow rather than to
     * force. Gone from the drops it is simply gone, and {@code tracker-give-on-respawn} hands the
     * Hunter a fresh one a moment later.
     *
     * <p>Not gated on the dead player being a Hunter: this strips the module's own item and nothing
     * else, so the rule is the simpler "ours never drops, ever" rather than one that has to be right
     * about the moment as well.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(tracker::isTracker);
    }

    /** A Hunter who died gets a replacement compass, if the owner allows one. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        tracker.giveOnRespawn(event.getPlayer());
    }

    /** Somebody leaving takes their pick with them — a stale one would aim a returning Hunter's
     *  compass at whoever happens to hold that id next hunt. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracker.forget(event.getPlayer().getUniqueId());
    }

    public String describe() {
        return "remembering which portal a Runner took, and the Hunters' right-click on the compass";
    }
}
