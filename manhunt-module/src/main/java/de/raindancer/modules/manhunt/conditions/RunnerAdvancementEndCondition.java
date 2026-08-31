package de.raindancer.modules.manhunt.conditions;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.speedrun.SpeedrunEndCondition;
import de.raindancer.modules.speedrun.SpeedrunSession;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Ends a run when any Runner — never a Hunter — completes the configured advancement. The Hunter-side
 * equivalent of {@code speedrun-module}'s own {@code AdvancementEndCondition}, filtered to the Runner
 * roster rather than to every participant: a Hunter racing the same dragon kill for sport does not
 * end the match.
 */
public final class RunnerAdvancementEndCondition implements SpeedrunEndCondition, Listener {

    private final Plugin plugin;
    private final NamespacedKey advancement;
    private final Set<UUID> runners;
    private SpeedrunSession session;

    public RunnerAdvancementEndCondition(Plugin plugin, NamespacedKey advancement, Set<UUID> runners) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.advancement = Objects.requireNonNull(advancement, "advancement");
        this.runners = Set.copyOf(Objects.requireNonNull(runners, "runners"));
    }

    @Override
    public void arm(SpeedrunSession session) {
        this.session = Objects.requireNonNull(session, "session");
        // Otherwise a Runner who already has it from an earlier run is never granted it again, and
        // this waits forever — see speedrun-module's own GoalAdvancement, which this repeats rather
        // than reuses: it is package-private there, built for speedrun-module's own end conditions.
        revokeGoalFromEveryRunner();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private void revokeGoalFromEveryRunner() {
        Advancement found = Bukkit.getAdvancement(advancement);
        if (found == null) {
            return;   // a datapack goal this server does not have; the condition simply never fires
        }
        for (UUID id : runners) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            // Folia: a run arms on whatever thread the countdown owned, not on each Runner's own.
            Scheduling.entity(plugin, player, () -> {
                AdvancementProgress progress = player.getAdvancementProgress(found);
                for (String criterion : Set.copyOf(progress.getAwardedCriteria())) {
                    progress.revokeCriteria(criterion);
                }
            });
        }
    }

    @Override
    public void disarm() {
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!advancement.equals(event.getAdvancement().getKey())) {
            return;
        }
        if (!runners.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        session.finish("advancement:" + advancement);
    }

    @Override
    public String describe() {
        return "advancement:" + advancement + " (runners)";
    }
}
