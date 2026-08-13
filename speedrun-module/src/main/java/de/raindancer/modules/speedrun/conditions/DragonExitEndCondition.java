package de.raindancer.modules.speedrun.conditions;

import de.raindancer.modules.speedrun.SpeedrunEndCondition;
import de.raindancer.modules.speedrun.SpeedrunSession;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Ends a run the way an actual dragon-kill speedrun is judged: not the instant the dragon dies, but
 * the moment a participant steps into the exit portal that spawns afterwards.
 *
 * <h2>Why two events instead of one</h2>
 * {@code minecraft:end/kill_dragon} fires the moment the dragon's health hits zero, wherever every
 * participant happens to be standing — some runners are still crossing the platform to the portal
 * when it does. Timing the run to that advancement, the way plain {@link AdvancementEndCondition}
 * does for every other goal, would stop the clock before the run everybody actually agrees on is over.
 * So this arms two handlers: the advancement only raises a flag, and {@link #onExitPortal} is the one
 * that calls {@link SpeedrunSession#finish}.
 *
 * <h2>Why the flag is shared across all participants, not kept per player</h2>
 * The dragon-kill advancement is granted to whoever is credited with the kill, not to the whole party,
 * so requiring the <em>same</em> player to both land the kill and take the portal would strand a
 * teammate who happened to deal the final hit while somebody else was still fighting. Once anybody
 * racing has the advancement, any participant reaching the portal ends it — the same "first past the
 * post, for the whole roster" rule {@link AdvancementEndCondition} already uses.
 *
 * <h2>Telling the two portal directions apart</h2>
 * {@link PlayerPortalEvent} with {@link PlayerTeleportEvent.TeleportCause#END_PORTAL} fires both for
 * stepping into an end portal in the Overworld (entering the End) and for stepping into the exit
 * portal in the End (leaving it) — Bukkit does not distinguish them by cause. What does distinguish
 * them is where the player already is: only the return trip has {@code event.getFrom()} in
 * {@link World.Environment#THE_END}.
 */
public final class DragonExitEndCondition implements SpeedrunEndCondition, Listener {

    private final Plugin plugin;
    private final NamespacedKey dragonKill;
    private SpeedrunSession session;
    private volatile boolean dragonKilled;

    public DragonExitEndCondition(Plugin plugin, NamespacedKey dragonKill) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dragonKill = Objects.requireNonNull(dragonKill, "dragonKill");
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
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!dragonKill.equals(event.getAdvancement().getKey())) {
            return;
        }
        if (!session.participants().contains(event.getPlayer().getUniqueId())) {
            return;
        }
        dragonKilled = true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onExitPortal(PlayerPortalEvent event) {
        if (!dragonKilled) {
            return;
        }
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return;
        }
        World from = event.getFrom().getWorld();
        if (from == null || from.getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!session.participants().contains(event.getPlayer().getUniqueId())) {
            return;
        }
        session.finish("advancement:" + dragonKill);
    }

    @Override
    public String describe() {
        return "advancement:" + dragonKill + " + exit portal";
    }
}
