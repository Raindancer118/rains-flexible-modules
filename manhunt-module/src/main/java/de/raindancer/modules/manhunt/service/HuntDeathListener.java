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
import java.util.function.IntSupplier;

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
    private final IntSupplier lives;
    private final HunterHoldListener hold;
    private final IntSupplier respawnWaitSeconds;
    private final HuntWatcher watcher;

    public HuntDeathListener(Plugin plugin, Hunt hunt, SpeedrunSession session,
                             Eliminations eliminations, Messages messages) {
        this(plugin, hunt, session, eliminations, messages, () -> 1, null, () -> 0, HuntWatcher.NONE);
    }

    /**
     * @param lives              how many deaths catch a Runner, read at each death
     * @param hold               where a Hunter waits out {@code respawnWaitSeconds}; null for no wait
     * @param watcher            told every death and what it cost
     */
    public HuntDeathListener(Plugin plugin, Hunt hunt, SpeedrunSession session, Eliminations eliminations,
                             Messages messages, IntSupplier lives, HunterHoldListener hold,
                             IntSupplier respawnWaitSeconds, HuntWatcher watcher) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.hunt = Objects.requireNonNull(hunt, "hunt");
        this.session = Objects.requireNonNull(session, "session");
        this.eliminations = Objects.requireNonNull(eliminations, "eliminations");
        this.messages = messages;
        this.lives = Objects.requireNonNull(lives, "lives");
        this.hold = hold;
        this.respawnWaitSeconds = Objects.requireNonNull(respawnWaitSeconds, "respawnWaitSeconds");
        this.watcher = Objects.requireNonNull(watcher, "watcher");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (session.state() != SpeedrunState.RUNNING) {
            return;
        }
        Player dead = event.getEntity();
        UUID id = dead.getUniqueId();
        Player killer = dead.getKiller();
        UUID by = killer == null ? null : killer.getUniqueId();
        String byName = killer == null ? null : killer.getName();
        if (hunt.isHunter(id)) {
            watcher.died(hunt, id, dead.getName(), by, byName, HuntWatcher.Death.HUNTER_DIED, 0);
            return;
        }
        if (!hunt.isRunner(id) || hunt.isEliminated(id)) {
            return;   // somebody not in this hunt, or a Runner already out
        }
        int left = lives.getAsInt() - hunt.recordDeath(id);
        if (left > 0) {
            tell("manhunt.life-lost", "runner", dead.getName(), "lives", String.valueOf(left));
            watcher.died(hunt, id, dead.getName(), by, byName, HuntWatcher.Death.LIFE_LOST, left);
            return;
        }
        hunt.eliminate(id);
        announceCaught(dead.getName(), hunt.livingRunners().size());
        watcher.died(hunt, id, dead.getName(), by, byName, HuntWatcher.Death.CAUGHT, 0);
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
            return;
        }
        int wait = respawnWaitSeconds.getAsInt();
        if (hold != null && wait > 0 && hunt.isHunter(player.getUniqueId())) {
            hold.holdFor(player.getUniqueId(), wait);
            if (messages != null) {
                messages.send(player, "manhunt.respawn-hold", "seconds", String.valueOf(wait));
            }
        }
    }

    private void tell(String key, String... placeholders) {
        if (messages == null) {
            return;
        }
        for (UUID id : hunt.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                messages.send(player, key, (Object[]) placeholders);
            }
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
