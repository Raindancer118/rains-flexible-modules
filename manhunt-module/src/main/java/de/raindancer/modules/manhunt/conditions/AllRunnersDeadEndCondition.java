package de.raindancer.modules.manhunt.conditions;

import de.raindancer.modules.speedrun.SpeedrunEndCondition;
import de.raindancer.modules.speedrun.SpeedrunSession;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ends the run for the Hunters — {@link ManhuntSettings.HunterWinCondition#ALL_RUNNERS_DEAD} — the
 * moment every Runner has died at least once this run.
 *
 * <p>The Hunter-side equivalent of {@code speedrun-module}'s {@code DeathEndCondition.DeathPolicy.ALL},
 * except the roster it watches is the Runner team, never the whole participant list — a Hunter dying
 * to a creeper mid-hunt does not end the match, and the last remaining Runner is exactly the "last
 * stand" moment a Manhunt is supposed to have.
 */
public final class AllRunnersDeadEndCondition implements SpeedrunEndCondition, Listener {

    private final Plugin plugin;
    private final Set<UUID> runners;
    /** Who has died so far this run — cleared on {@link #disarm}. */
    private final Set<UUID> dead = ConcurrentHashMap.newKeySet();
    private SpeedrunSession session;

    public AllRunnersDeadEndCondition(Plugin plugin, Set<UUID> runners) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runners = Set.copyOf(Objects.requireNonNull(runners, "runners"));
        if (this.runners.isEmpty()) {
            throw new IllegalArgumentException("a Manhunt needs at least one Runner");
        }
    }

    @Override
    public void arm(SpeedrunSession session) {
        this.session = Objects.requireNonNull(session, "session");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disarm() {
        HandlerList.unregisterAll(this);
        dead.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        UUID player = event.getEntity().getUniqueId();
        if (!runners.contains(player)) {
            return;
        }
        dead.add(player);
        if (everyRunnerHasDied(runners, dead)) {
            session.finish("all-runners-dead");
        }
    }

    /** Pulled out as a pure function so the "is the hunt over" decision is testable without a server. */
    static boolean everyRunnerHasDied(Set<UUID> runners, Set<UUID> dead) {
        return dead.containsAll(runners);
    }

    @Override
    public String describe() {
        return "all-runners-dead";
    }
}
