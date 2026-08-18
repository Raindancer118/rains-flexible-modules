package de.raindancer.modules.speedrun.conditions;

import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Takes the goal advancement back off every racer as a run arms itself.
 *
 * <h2>Why a run has to do this at all</h2>
 * An advancement belongs to the player, not to the world, and no world reset touches it. A racer who
 * has ever earned {@code end/kill_dragon} — on this server, in an earlier run, months ago — simply is
 * never granted it a second time, so {@link org.bukkit.event.player.PlayerAdvancementDoneEvent} never
 * fires for them again. Every end condition built on that grant then waits for something that can no
 * longer happen: the clock runs on past the kill, past the portal, until somebody stops it by hand.
 * That was a real run. Clearing the goal first is what makes the second run behave like the first.
 *
 * <p>Only the goal itself is cleared, never a racer's other progress — this is about making one
 * advancement grantable again, not about wiping somebody's history.
 */
final class GoalAdvancement {

    private GoalAdvancement() {
    }

    static void revokeFor(Plugin plugin, NamespacedKey goal, Collection<UUID> participants) {
        Advancement advancement = Bukkit.getAdvancement(goal);
        if (advancement == null) {
            return;   // a datapack goal this server does not have; the condition simply never fires
        }
        for (UUID id : participants) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            // Folia: a run starts on whatever thread the countdown owned, not on each racer's own.
            Scheduling.entity(plugin, player, () -> {
                AdvancementProgress progress = player.getAdvancementProgress(advancement);
                for (String criterion : Set.copyOf(progress.getAwardedCriteria())) {
                    progress.revokeCriteria(criterion);
                }
            });
        }
    }
}
