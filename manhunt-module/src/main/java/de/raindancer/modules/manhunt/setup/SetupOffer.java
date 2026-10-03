package de.raindancer.modules.manhunt.setup;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.stats.HistoryStore;
import de.raindancer.modules.manhunt.stats.StatsStore;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Objects;

/**
 * Offers the setup wizard to an admin logging in on a server that has never run a hunt and never
 * been set up — a clickable line, with "not now" beside it. A server with hunts on record is
 * clearly set up already, so it is never asked.
 */
public final class SetupOffer implements Listener {

    private final SetupState setup;
    private final StatsStore stats;
    private final HistoryStore history;
    private final Messages messages;

    public SetupOffer(SetupState setup, StatsStore stats, HistoryStore history, Messages messages) {
        this.setup = Objects.requireNonNull(setup, "setup");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.history = Objects.requireNonNull(history, "history");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** Whether this server still wants the wizard. */
    public boolean fresh() {
        return !setup.done() && history.all().isEmpty() && history.nextNumber() == 1
                && stats.top(StatsStore.Board.RATING, 1).isEmpty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(PermissionNodes.ADMIN) && fresh()) {
            messages.send(player, "manhunt.setup.offer");
        }
    }
}
