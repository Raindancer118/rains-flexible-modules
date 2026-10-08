package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.actionbar.ActionBarPriority;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.TransactionKind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.UUID;

/** "+⛃5.00 · ⛃105.00" above the hotbar whenever a balance moves, on the player's own thread. */
public final class BalanceNotifier implements RainEconomy.BalanceWatcher, IEconomyService {

    private final Plugin plugin;
    private final Server server;
    private final ActionBars bars;
    private volatile EconomySettings settings;

    public BalanceNotifier(Plugin plugin, Server server, ActionBars bars, EconomySettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.bars = bars;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    @Override
    public void moved(UUID who, Money delta, Money balance, TransactionKind kind) {
        EconomySettings live = settings;
        if (!live.balanceOnActionBar()) {
            return;
        }
        Player player = server.getPlayer(who);
        if (player == null) {
            return;
        }
        Currency currency = live.currency();
        Component line = Component.text()
                .append(Component.text(delta.isNegative() ? "-" : "+",
                        delta.isNegative() ? NamedTextColor.RED : NamedTextColor.GREEN))
                .append(currency.render(delta.isNegative() ? delta.negate() : delta))
                .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                .append(currency.render(balance))
                .build();
        Scheduling.entity(plugin, player, () -> bars.show(who, "economy", line, Duration.ofMillis(2500),
                ActionBarPriority.NORMAL));
    }
}
