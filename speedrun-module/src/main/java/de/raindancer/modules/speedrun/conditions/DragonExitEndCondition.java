package de.raindancer.modules.speedrun.conditions;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.speedrun.SpeedrunEndCondition;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunWorlds;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

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
 *
 * <h2>Only the run's own End</h2>
 * Built with the run's {@link SpeedrunWorlds}, the dragon, the exit portal and the credits only count
 * in the run's End: a dragon killed in the server's own End, by anybody, once armed the run's portal.
 */
public final class DragonExitEndCondition implements SpeedrunEndCondition, Listener {

    private static final LogChannel LOG =
            Log.of("speedrun");

    private final Plugin plugin;
    private final NamespacedKey dragonKill;
    private final Predicate<UUID> counts;
    /** {@code null}: any End counts — the shape before the condition knew the run's worlds. */
    private final SpeedrunWorlds runWorlds;
    private SpeedrunSession session;
    private volatile boolean dragonKilled;

    public DragonExitEndCondition(Plugin plugin, NamespacedKey dragonKill) {
        this(plugin, dragonKill, participant -> true);
    }

    /**
     * @param counts who leaving the End actually ends the run for — see
     *               {@link de.raindancer.modules.speedrun.SpeedrunMode#countsForGoal}. The dragon
     *               dying still arms the portal whoever landed the hit, including a Hunter: the flag
     *               is a fact about the world, and it is the walk out that is somebody's win.
     */
    public DragonExitEndCondition(Plugin plugin, NamespacedKey dragonKill, Predicate<UUID> counts) {
        this(plugin, dragonKill, counts, null);
    }

    /** The same, counting only the dragon, the exit portal and the credits of {@code runWorlds}' End. */
    public DragonExitEndCondition(Plugin plugin, NamespacedKey dragonKill, Predicate<UUID> counts,
                                  SpeedrunWorlds runWorlds) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.dragonKill = Objects.requireNonNull(dragonKill, "dragonKill");
        this.counts = Objects.requireNonNull(counts, "counts");
        this.runWorlds = runWorlds;
    }

    /**
     * The dragon died before this condition was armed — a run resumed after a restart, in an End
     * whose fight was already won. Without this the exit portal would end nothing and the run could
     * never be won.
     */
    public void dragonAlreadyKilled() {
        dragonKilled = true;
    }

    /** Whether leaving the End as {@code participant} ends the run — the game mode's answer. */
    public boolean counts(UUID participant) {
        return counts.test(participant);
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
        if (!session.participants().contains(event.getPlayer().getUniqueId())
                || !inRunWorlds(event.getPlayer().getWorld())) {
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
        if (event.getEntity() instanceof EnderDragon dragon && isRunsEnd(dragon.getWorld())) {
            dragonKilled = true;
        }
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
        if (from == null || from.getEnvironment() != World.Environment.THE_END || !isRunsEnd(from)) {
            return;
        }
        reached(event.getPlayer(), "the exit portal");
    }

    /**
     * The respawn after the end credits — the second way in, and the reliable one.
     *
     * <h2>Why this and not "changed world out of the End"</h2>
     * The exit portal runs the credits and then respawns the player with
     * {@link PlayerRespawnEvent.RespawnReason#END_PORTAL}; whether a {@link PlayerPortalEvent} fires
     * on the way in has never been something to rely on. This used to be caught one step later as
     * "was in the End, is somewhere else now" — which is also true after dying there, a command, a
     * plugin, or the lobby sending a reconnecting player home, and each of those ended a hunt as the
     * Runners' win. Only the player who actually walked through is respawned for this reason.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEndCredits(PlayerRespawnEvent event) {
        if (!dragonKilled || event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.END_PORTAL
                || !isRunsEnd(event.getPlayer().getWorld())) {
            return;
        }
        reached(event.getPlayer(), "the respawn after the end credits");
    }

    private void reached(Player player, String how) {
        if (!isGoalReacher(player.getUniqueId())) {
            return;
        }
        // Named in the log, so a finish anybody doubts can be checked afterwards.
        LOG.info("The run was finished by {} ({}): {} after the dragon died.",
                player.getName(), player.getUniqueId(), how);
        session.finish("advancement:" + dragonKill);
    }

    private boolean isRunsEnd(World world) {
        return runWorlds == null || (world != null && runWorlds.theEnd().equalsIgnoreCase(world.getName()));
    }

    private boolean inRunWorlds(World world) {
        return runWorlds == null || (world != null && runWorlds.contains(world.getName()));
    }

    private boolean isGoalReacher(UUID player) {
        return session.participants().contains(player) && counts.test(player);
    }

    @Override
    public String describe() {
        return "advancement:" + dragonKill + " + exit portal";
    }
}
