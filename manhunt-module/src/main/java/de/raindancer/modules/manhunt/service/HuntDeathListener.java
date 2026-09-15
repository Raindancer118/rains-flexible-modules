package de.raindancer.modules.manhunt.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;

/**
 * A death, in a hunt: a Runner is out for good, a Hunter is back in a moment.
 *
 * <h2>Why the last Runner ends the run here and not in an end condition</h2>
 * Because the two halves are one fact. {@code SpeedrunEndCondition} is the right shape for a rule that
 * only watches ("has anybody got this advancement"); this also *changes* the hunt — it is what makes a
 * Runner eliminated at all — and splitting the writing from the asking is exactly the bug the module
 * this replaces had: a condition that counted deaths of its own, next to a listener that counted them
 * too, each a tick out of step with the other.
 *
 * <h2>Spectator on respawn, never on death</h2>
 * A dead player has no game mode to change yet — vanilla hands them one when they respawn, over the
 * top of anything set in between. So the death only records the elimination, and
 * {@link #onRespawn} is what actually stands them down.
 */
public final class HuntDeathListener implements Listener {

    /** What {@link SpeedrunSession#finish} is told when the last Runner is caught. */
    public static final String HUNTERS_WIN = "manhunt:caught";

    private final Plugin plugin;
    private final Hunt hunt;
    private final SpeedrunSession session;
    private final Eliminations eliminations;
    private final Messages messages;

    public HuntDeathListener(Plugin plugin, Hunt hunt, SpeedrunSession session,
                             Eliminations eliminations, Messages messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.hunt = Objects.requireNonNull(hunt, "hunt");
        this.session = Objects.requireNonNull(session, "session");
        this.eliminations = Objects.requireNonNull(eliminations, "eliminations");
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (session.state() != SpeedrunState.RUNNING) {
            return;
        }
        Player dead = event.getEntity();
        if (!hunt.eliminate(dead.getUniqueId())) {
            return;   // a Hunter, somebody not in this hunt, or a Runner already out
        }
        announceCaught(dead.getName(), hunt.livingRunners().size());
        if (hunt.allRunnersOut()) {
            session.finish(HUNTERS_WIN);
        }
    }

    /** An eliminated Runner comes back to watch — unless the hunt they were in is already over. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (session.state() != SpeedrunState.RUNNING) {
            return;   // the last Runner's own respawn: the hunt ended with their death, so they play on
        }
        Player player = event.getPlayer();
        if (hunt.isEliminated(player.getUniqueId())) {
            eliminations.spectate(player);
        }
    }

    private void announceCaught(String name, int left) {
        if (messages == null) {
            return;
        }
        for (UUID id : hunt.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                messages.send(player, left == 0 ? "manhunt.last-runner" : "manhunt.caught",
                        "runner", name, "left", String.valueOf(left));
            }
        }
    }

    public String describe() {
        return "eliminating a caught Runner, and ending the hunt when the last of them is out";
    }
}
