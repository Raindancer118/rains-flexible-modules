package de.raindancer.modules.manhunt.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * A Runner who logs out and stays away ({@code ManhuntSettings.runnerOfflineGraceSeconds}) counts as
 * caught — without this a hunt whose Runners all left could never end.
 *
 * <p>Registered per run. Each absence gets a number; a timer only catches the absence it was started
 * for, so somebody who leaves, comes back and leaves again is given the whole grace again. Hunters
 * may stay away for good: the hunt goes on, and the Runners can still win at the goal.
 */
public final class AbsentRunners implements Listener {

    private final Plugin plugin;
    private final Hunt hunt;
    private final SpeedrunSession session;
    private final BooleanSupplier stillThisHunt;
    private final IntSupplier graceSeconds;
    private final BiConsumer<Long, Runnable> later;
    private final Messages messages;
    private final Map<UUID, Long> absences = new ConcurrentHashMap<>();

    public AbsentRunners(Plugin plugin, Hunt hunt, SpeedrunSession session, BooleanSupplier stillThisHunt,
                         IntSupplier graceSeconds, BiConsumer<Long, Runnable> later, Messages messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.hunt = Objects.requireNonNull(hunt, "hunt");
        this.session = Objects.requireNonNull(session, "session");
        this.stillThisHunt = Objects.requireNonNull(stillThisHunt, "stillThisHunt");
        this.graceSeconds = Objects.requireNonNull(graceSeconds, "graceSeconds");
        this.later = Objects.requireNonNull(later, "later");
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        away(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        back(event.getPlayer().getUniqueId());
    }

    /** {@code runner} has gone — kicked, quit, or already offline when the hunt began. */
    public void away(UUID runner) {
        int seconds = graceSeconds.getAsInt();
        if (seconds <= 0 || !hunt.isRunner(runner) || hunt.isEliminated(runner)) {
            return;
        }
        long absence = absences.merge(runner, 1L, Long::sum);
        later.accept(seconds * 20L, () -> stayedAway(runner, absence));
    }

    /** {@code runner} is back: whatever absence was counting is over. */
    public void back(UUID runner) {
        absences.merge(runner, 1L, Long::sum);
    }

    private void stayedAway(UUID runner, long absence) {
        if (absences.getOrDefault(runner, 0L) != absence
                || plugin.getServer().getPlayer(runner) != null
                || !stillThisHunt.getAsBoolean()
                || session.state() == SpeedrunState.FINISHED
                || !hunt.eliminate(runner)) {
            return;
        }
        int left = hunt.livingRunners().size();
        if (messages != null) {
            String name = plugin.getServer().getOfflinePlayer(runner).getName();
            for (UUID id : hunt.everybody()) {
                Player player = plugin.getServer().getPlayer(id);
                if (player != null) {
                    messages.send(player, "manhunt.caught-away", "runner", name == null ? "A Runner" : name,
                            "left", String.valueOf(left));
                }
            }
        }
        if (hunt.allRunnersOut()) {
            session.finish(HuntDeathListener.HUNTERS_WIN);
        }
    }

    public String describe() {
        return "catching a Runner who stays logged out longer than the grace";
    }
}
