package de.raindancer.modules.speedrun;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Keeps a death inside the run's own three worlds, the way {@link SpeedrunPortalListener} keeps
 * portal travel inside them.
 *
 * <h2>The bug this exists for</h2>
 * A racer killed in the run's nether or end woke up in the <em>server's</em> overworld: out of the
 * race, in a world no reset ever touches, with the run still counting. Minecraft respawns a player
 * with no valid respawn point of their own at the primary level's spawn, and a world made at runtime
 * is never that. {@link SpeedrunLobby#start} does hand every participant a respawn point where they
 * started, but that only covers the people who were standing in the lobby when somebody pressed the
 * block — not a spectator, not somebody who walked in mid-run, and not a point the run's own reset
 * has since regenerated out from under them. This is the floor under all of that: whatever went
 * wrong with the respawn point, a death in the run comes back into the run.
 *
 * <p>Runs at {@link EventPriority#HIGH}, before {@link SpeedrunLobbyListener#onRespawn} reads the
 * location at {@link EventPriority#MONITOR} to decide whether to hand the lobby items back — the
 * corrected world is what that check is written for.
 *
 * <h2>What is left alone</h2>
 * A respawn point already inside the family (a bed in the run's overworld, an anchor in its nether,
 * the point {@link SpeedrunLobby#start} wrote) is somebody's own choice inside the race, so it
 * stands. So does a death anywhere outside the family, and so does everything if the lobby world is
 * not loaded to send them to — a redirect that cannot be completed correctly is worse than none.
 *
 * @see SpeedrunWorlds
 */
public final class SpeedrunRespawnListener implements Listener {

    private final SpeedrunLobby lobby;

    public SpeedrunRespawnListener(SpeedrunLobby lobby) {
        this.lobby = lobby;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        SpeedrunWorlds worlds = SpeedrunWorlds.around(lobby.config().worldName());
        if (!worlds.contains(diedIn(event.getPlayer()))) {
            return;
        }
        if (worlds.contains(nameOf(event.getRespawnLocation()))) {
            return;
        }
        lobby.wayBackIn().ifPresent(event::setRespawnLocation);
    }

    /**
     * Where the death happened — the player's last death location, which the server records before
     * this event fires. Falls back to where the player is now for the case it is missing (a death
     * before the server ever recorded one, a world since unloaded): during a respawn that is already
     * the destination, so the fallback can only ever decline to redirect, never redirect wrongly.
     */
    private static String diedIn(Player player) {
        String recorded = nameOf(player.getLastDeathLocation());
        return recorded != null ? recorded : player.getWorld().getName();
    }

    private static String nameOf(Location location) {
        if (location == null) {
            return null;
        }
        World world;
        try {
            world = location.getWorld();
        } catch (IllegalArgumentException unloaded) {
            // A Location in a world that has since been unloaded throws rather than answering null.
            return null;
        }
        return world == null ? null : world.getName();
    }
}
