package de.raindancer.modules.manhunt.conditions;

import de.raindancer.modules.speedrun.SpeedrunEndCondition;
import de.raindancer.modules.speedrun.SpeedrunSession;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Ends a run the moment any Runner leaves The End — {@link ManhuntSettings.RunnerWinCondition#PORTAL_EXIT}.
 *
 * <p>Deliberately not gated on the dragon being dead first: unlike {@code speedrun-module}'s own
 * {@code DragonExitEndCondition}, reaching the exit portal at all <em>is</em> the Runners' objective
 * here, not a formality after an advancement. A server that wants the dragon kill enforced too
 * chooses {@link ManhuntSettings.RunnerWinCondition#ADVANCEMENT} with the vanilla dragon-kill key
 * instead, or — for the classic shape — arms both, and whichever one a Runner actually satisfies
 * first is the one {@link SpeedrunSession#finish} keeps.
 *
 * <h2>Why two events, both watched</h2>
 * See {@code DragonExitEndCondition}'s own javadoc: stepping into the exit portal runs the credits
 * sequence and sends the player to their respawn point, and which Bukkit event that arrives as has
 * never been reliable enough to bet a run's ending on. {@link #onLeavingTheEnd} — anybody leaving The
 * End at all, however they left — is the one that cannot miss; {@link #onExitPortal} is the ordinary
 * path, caught one step earlier. Harmless when both fire: {@link SpeedrunSession#finish} only counts
 * the first.
 */
public final class RunnerExitEndCondition implements SpeedrunEndCondition, Listener {

    private final Plugin plugin;
    private final Set<UUID> runners;
    private SpeedrunSession session;

    public RunnerExitEndCondition(Plugin plugin, Set<UUID> runners) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runners = Set.copyOf(Objects.requireNonNull(runners, "runners"));
    }

    @Override
    public void arm(SpeedrunSession session) {
        this.session = Objects.requireNonNull(session, "session");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disarm() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeavingTheEnd(PlayerChangedWorldEvent event) {
        if (event.getFrom().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!runners.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        session.finish("portal-exit");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExitPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return;
        }
        World from = event.getFrom().getWorld();
        if (from == null || from.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!runners.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        session.finish("portal-exit");
    }

    @Override
    public String describe() {
        return "portal-exit (runners)";
    }
}
