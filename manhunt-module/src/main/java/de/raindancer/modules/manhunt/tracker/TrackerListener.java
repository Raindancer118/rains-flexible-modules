package de.raindancer.modules.manhunt.tracker;

import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Point;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

/**
 * The tracking compass' moments in a Bukkit event: a Runner going through a portal, a Hunter
 * right-clicking their compass, a Hunter dying and respawning, and either of them leaving.
 *
 * <h2>Why the portal is recorded here rather than in {@link TrackerCompassService}</h2>
 * Same split the rest of this module already has: a listener turns an event into a plain fact — "this
 * Runner left this spot in this world" — and {@link PortalMemory} stores it without ever seeing a
 * Bukkit type. That is what lets the compass be aimed at a door in
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
        // Hunters too, not only Runners: with the team compass on, a teammate below is followed
        // through the door they took exactly as a Runner is.
        if (!hunt.everybody().contains(player.getUniqueId()) || hunt.isEliminated(player.getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        if (from == null || from.getWorld() == null) {
            return;
        }
        portals.remember(player.getUniqueId(),
                new Point(from.getWorld().getName(), from.getX(), from.getY(), from.getZ()));
    }

    /** A Hunter right-clicking the compass follows the next one along; sneaking, it opens the list —
     *  see {@link TrackerCompassService#cycleTarget} and {@code openPicker}, which decide whether
     *  that is allowed at all. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (tracker.team().map(team -> team.isTeamCompass(event.getItem())).orElse(false)) {
            // The team compass is a button too: cycle, or the list when sneaking.
            event.setCancelled(true);
            TeamCompassService team = tracker.team().get();
            if (event.getPlayer().isSneaking()) {
                team.openPicker(event.getPlayer());
            } else {
                team.cycle(event.getPlayer());
            }
            return;
        }
        if (!tracker.isTracker(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        if (!tracker.isHolder(hunt, player.getUniqueId())) {
            return;
        }
        // The compass is a button here, not a block-placing item: a right-click on a lodestone would
        // otherwise bind it for real and undo the aim on the very next sweep.
        event.setCancelled(true);
        if (player.isSneaking()) {
            tracker.openPicker(player);
        } else {
            tracker.cycleTarget(player);
        }
    }

    /**
     * A Hunter who died gets a replacement compass, if the owner allows one — and their needle is
     * sent again.
     *
     * <p>The resend is not belt-and-braces: respawning is one of the moments the server sends that
     * client a spawn position of its own, which replaces the compass target the sweep last sent while
     * the sweep still believes it is showing. See {@link TrackerCompassService#resyncNeedle}.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        tracker.giveOnRespawn(event.getPlayer());
        tracker.team().ifPresent(team -> team.giveOnRespawn(event.getPlayer()));
        tracker.resyncNeedle(event.getPlayer().getUniqueId());
    }

    /** The other moment the server overwrites a client's spawn position — see {@link #onRespawn}. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        tracker.resyncNeedle(event.getPlayer().getUniqueId());
    }

    /** Somebody leaving takes their pick with them — a stale one would aim a returning Hunter's
     *  compass at whoever happens to hold that id next hunt. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracker.forget(event.getPlayer().getUniqueId());
        tracker.team().ifPresent(team -> team.forget(event.getPlayer().getUniqueId()));
    }

    public String describe() {
        return "remembering which portal a Runner took, and the Hunters' right-click on the compass";
    }
}
