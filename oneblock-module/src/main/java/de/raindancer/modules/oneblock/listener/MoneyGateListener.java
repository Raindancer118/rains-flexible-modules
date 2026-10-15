package de.raindancer.modules.oneblock.listener;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.store.IslandBook;
import de.raindancer.modules.oneblock.util.PermissionNodes;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * SMP money on the island server only for islands that chose it. The economy itself knows nothing of islands, so the
 * commands that reach it are what is gated: {@code /shop}, {@code /pay} and the rest of the configured list.
 */
public final class MoneyGateListener implements IOneBlockListener {

    private final IslandBook book;
    private final Messages messages;
    private final Supplier<OneBlockSettings> settings;

    public MoneyGateListener(IslandBook book, Messages messages, Supplier<OneBlockSettings> settings) {
        this.book = book;
        this.messages = messages;
        this.settings = settings;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!settings.get().isMoneyCommand(event.getMessage()) || player.hasPermission(PermissionNodes.ADMIN)) {
            return;
        }
        Optional<EconomyMode> mode = book.ofPlayer(player.getUniqueId()).map(Island::mode);
        if (mode.filter(EconomyMode::allowsSmpMoney).isPresent()) {
            return;
        }
        event.setCancelled(true);
        messages.send(player, mode.isEmpty() ? "oneblock.money.no-island"
                : mode.get() == EconomyMode.UNDECIDED ? "oneblock.money.undecided" : "oneblock.money.pure");
    }

    /** Nothing kept per player. */
    @Override
    public void forget(UUID player) {
    }
}
