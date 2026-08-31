package de.raindancer.modules.manhunt.conditions;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.service.ManhuntLives;
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

/**
 * Ends the run for the Hunters — {@link ManhuntSettings.HunterWinCondition#ALL_RUNNERS_DEAD} — the
 * moment every Runner has died at least once this run.
 *
 * <p>The Hunter-side equivalent of {@code speedrun-module}'s {@code DeathEndCondition.DeathPolicy.ALL},
 * except the roster it watches is the Runner team, never the whole participant list — a Hunter dying
 * to a creeper mid-hunt does not end the match, and the last remaining Runner is exactly the "last
 * stand" moment a Manhunt is supposed to have.
 *
 * <h2>"Dead" means whatever the death rule says it means</h2>
 * The question is not "has this Runner died" but "is this Runner out", and {@link ManhuntLives} is
 * the one place that knows the difference: under {@code ELIMINATE} the first death answers yes, under
 * {@code LIVES} the last one does, and under {@code RESPAWN} nothing ever does — which is why this
 * condition is only ever armed alongside a rule that can actually put somebody out. Counting deaths
 * here as well would be a second, quietly diverging copy of that bookkeeping; this class only
 * <em>asks</em>, on every death, whether the board says the hunt is over.
 */
public final class AllRunnersDeadEndCondition implements SpeedrunEndCondition, Listener {

    private final Plugin plugin;
    private final Set<UUID> runners;
    private final ManhuntLives lives;
    private SpeedrunSession session;

    /**
     * @param lives the board that knows what a death costs. {@code null} falls back to "one death and
     *              you are out", which is what this condition meant before there were death rules and
     *              is exactly {@code ELIMINATE}.
     */
    public AllRunnersDeadEndCondition(Plugin plugin, Set<UUID> runners, ManhuntLives lives) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runners = Set.copyOf(Objects.requireNonNull(runners, "runners"));
        if (this.runners.isEmpty()) {
            throw new IllegalArgumentException("a Manhunt needs at least one Runner");
        }
        this.lives = lives != null ? lives
                : new ManhuntLives(ManhuntSettings.DEFAULTS
                        .withRunnerDeathRule(ManhuntSettings.RunnerDeathRule.ELIMINATE));
    }

    public AllRunnersDeadEndCondition(Plugin plugin, Set<UUID> runners) {
        this(plugin, runners, null);
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

    /**
     * Deliberately {@code MONITOR}, and deliberately a tick later: {@code ManhuntDeathListener} is the
     * one that <em>records</em> the death against {@link ManhuntLives}, and both run off the same
     * event. Asking the board in the same tick would race that write and read a board that is one
     * death behind — the last Runner would die and the hunt would carry on until somebody else did.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        UUID player = event.getEntity().getUniqueId();
        if (!runners.contains(player)) {
            return;
        }
        Scheduling.globalLater(plugin, 1L, () -> {
            if (session != null && lives.allOut(runners)) {
                session.finish("all-runners-dead");
            }
        });
    }

    @Override
    public String describe() {
        return "all-runners-dead";
    }
}
