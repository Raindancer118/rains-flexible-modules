package de.raindancer.modules.economy.listener;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.TransactionKind;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.EnumSet;
import java.util.UUID;

/** An account for everybody who joins, and "while you were away" for whoever was paid meanwhile. */
public final class AccountListener implements IEconomyListener {

    private static final EnumSet<TransactionKind> AWAY = EnumSet.of(TransactionKind.PAY, TransactionKind.BILL,
            TransactionKind.PLUGIN, TransactionKind.ADMIN, TransactionKind.LOTTERY, TransactionKind.GAMBLE, TransactionKind.WAGE,
            TransactionKind.AUCTION);

    private final EconomyServices services;

    public AccountListener(EconomyServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long lastSeen = player.getLastSeen();
        services.economy().open(player.getUniqueId(), player.getName());
        services.auctions().joined(player);
        services.auctions().deliver(player);
        if (lastSeen <= 0) {
            return;
        }
        Scheduling.async(services.plugin(), () -> {
            Money arrived = services.economy().book().arrivedSince(player.getUniqueId(), lastSeen, AWAY);
            if (arrived.isPositive()) {
                Scheduling.entity(services.plugin(), player, () -> services.messages().send(player,
                        "economy.away", "amount", services.currency().render(arrived)));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        services.payments().forget(player);
        services.bills().forget(player);
        services.gambling().forget(player);
        services.income().forget(player);
        services.sidebar().forget(player);
        services.core().actionBars().forget(player);
    }

    @Override
    public String describe() {
        return "opening accounts on join, and forgetting players on quit";
    }
}
