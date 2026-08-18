package de.raindancer.modules.speedrun.conditions;

import de.raindancer.modules.speedrun.SpeedrunEndCondition;
import de.raindancer.modules.speedrun.SpeedrunSession;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
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
 * So the kill only raises a flag, and {@link #onExitPortal} is the one that calls
 * {@link SpeedrunSession#finish}.
 *
 * <p>The flag is raised by the dragon actually dying ({@link #onDragonDeath}), not by the advancement
 * — an advancement is granted once per player and never again, so on the second run of anybody who
 * has killed a dragon here before, the flag never rose and the portal ended nothing.
 * {@link #onAdvancement} is kept as a second way in, and {@link GoalAdvancement} clears the goal as
 * the run arms so it can be granted at all.
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
        // So the advancement half can still fire for a racer who has killed the dragon before — the
        // kill itself is watched directly either way, see onDragonDeath.
        GoalAdvancement.revokeFor(plugin, dragonKill, session.participants());
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

    /**
     * The dragon dying, watched directly rather than only through {@link #onAdvancement}.
     *
     * <p>Advancements belong to the player and outlive any world reset: a racer who has ever killed
     * the dragon on this server before is simply never granted {@code end/kill_dragon} again, so the
     * advancement event never fires, the flag never rises, and stepping into the exit portal ended
     * nothing at all. That was a real run that never stopped its clock. The kill itself is the fact
     * the rule is actually about, and it happens exactly once per run, so this is what arms the portal;
     * {@link #onAdvancement} stays as the second way in for a modified dragon that somehow grants it
     * without a vanilla death.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon) {
            dragonKilled = true;
        }
    }

    /**
     * Leaving the End at all, once the dragon is dead — the same finish as {@link #onExitPortal},
     * caught one step later.
     *
     * <h2>Why this is not redundant</h2>
     * Stepping into the exit portal is not an ordinary portal trip: the server runs the credits and
     * sends the player to their respawn point, and which event that arrives as has never been
     * something to rely on — a {@link PlayerPortalEvent} that never fired is a run whose clock never
     * stopped, which is exactly what happened. A player standing in the overworld who was in the End
     * a moment ago has left through the only exit there is, and {@link PlayerChangedWorldEvent}
     * always fires for that, whatever moved them.
     *
     * <p>Harmless when both fire: {@link SpeedrunSession#finish} only counts the first.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLeavingTheEnd(PlayerChangedWorldEvent event) {
        if (!dragonKilled) {
            return;
        }
        if (event.getFrom().getEnvironment() != World.Environment.THE_END) {
            return;
        }
        if (!session.participants().contains(event.getPlayer().getUniqueId())) {
            return;
        }
        session.finish("advancement:" + dragonKill);
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
