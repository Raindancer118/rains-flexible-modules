package de.raindancer.modules.speedrun.conditions;

import de.raindancer.modules.speedrun.SpeedrunEndCondition;
import de.raindancer.modules.speedrun.SpeedrunSession;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Ends a run when a participant completes a chosen advancement — {@code minecraft:end/kill_dragon}
 * for a vanilla dragon-kill speedrun, or any other advancement key a caller wants to race for.
 *
 * <p>Not just any player's advancement: only a {@linkplain SpeedrunSession#participants() participant's}
 * counts, so a spectator or a staff member wandering the map cannot end somebody else's run.
 *
 * <p>And not every participant's either, where the game mode narrows it — see
 * {@link de.raindancer.modules.speedrun.SpeedrunMode#countsForGoal}. In Manhunt a Hunter is as much a
 * participant as a Runner, but a Hunter killing the dragon has won the Runners nothing, so the run
 * carries on. The two checks are deliberately both kept: the roster is who is playing at all, the
 * predicate is who this particular goal belongs to.
 */
public final class AdvancementEndCondition implements SpeedrunEndCondition, Listener {

    private final Plugin plugin;
    private final NamespacedKey advancement;
    private final Predicate<UUID> counts;
    private SpeedrunSession session;

    public AdvancementEndCondition(Plugin plugin, NamespacedKey advancement) {
        this(plugin, advancement, participant -> true);
    }

    public AdvancementEndCondition(Plugin plugin, NamespacedKey advancement, Predicate<UUID> counts) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.advancement = Objects.requireNonNull(advancement, "advancement");
        this.counts = Objects.requireNonNull(counts, "counts");
    }

    /** Whether this goal is {@code participant}'s to reach — the game mode's answer. */
    public boolean counts(UUID participant) {
        return counts.test(participant);
    }

    @Override
    public void arm(SpeedrunSession session) {
        this.session = Objects.requireNonNull(session, "session");
        // Otherwise a racer who already has it is never granted it again, and this waits forever.
        GoalAdvancement.revokeFor(plugin, advancement, session.participants());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
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
        UUID player = event.getPlayer().getUniqueId();
        if (!session.participants().contains(player) || !counts.test(player)) {
            return;
        }
        session.finish("advancement:" + advancement);
    }

    @Override
    public String describe() {
        return "advancement:" + advancement;
    }
}
