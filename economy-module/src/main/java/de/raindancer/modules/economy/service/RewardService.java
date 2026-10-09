package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Money given for playing rather than earned by selling: advancements and passive income, all through one
 * hourly cap. There is no pay for killing mobs or mining — what you gather, you sell.
 */
public final class RewardService implements IEconomyService {

    private final RainEconomy economy;
    private final EarningWindow window;
    private volatile EconomySettings settings;

    public RewardService(RainEconomy economy, EarningWindow window, EconomySettings settings) {
        this.economy = economy;
        this.window = window;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    public void advanced(Player player, String title) {
        EconomySettings live = settings;
        if (!live.advancementRewardsEnabled()) {
            return;
        }
        pay(player, live.advancementMoney(), "Advancement: " + title, TransactionKind.REWARD);
    }

    /** Pays through the hourly cap; what the cap or the account refuses is simply not paid. */
    EconomyResult pay(Player player, Money amount, String reason, TransactionKind kind) {
        if (!amount.isPositive() || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR || !player.hasPermission(PermissionNodes.EARN)) {
            return EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, Money.ZERO);
        }
        economy.open(player.getUniqueId(), player.getName());
        Money allowed = window.take(player.getUniqueId(), amount, settings.hourlyCapMoney());
        if (!allowed.isPositive()) {
            return EconomyResult.failed(EconomyResult.Outcome.REFUSED, amount, economy.balance(player.getUniqueId()));
        }
        // The levers (a low treasury, the stabiliser, a rich server) may pay less; the cap only counts what is paid.
        Money paying = de.raindancer.core.social.economy.EconomyLevers.faucet(sourceOf(kind), allowed);
        if (allowed.isMoreThan(paying)) {
            window.refund(player.getUniqueId(), allowed.minus(paying));
        }
        if (!paying.isPositive()) {
            return EconomyResult.failed(EconomyResult.Outcome.TREASURY_EMPTY, amount,
                    economy.balance(player.getUniqueId()));
        }
        EconomyResult result = economy.move(player.getUniqueId(), paying, kind, reason);
        if (!result.succeeded()) {
            window.refund(player.getUniqueId(), paying);
        }
        return result;
    }

    static String sourceOf(TransactionKind kind) {
        return kind == TransactionKind.INCOME ? de.raindancer.modules.economy.model.Sources.INCOME : de.raindancer.modules.economy.model.Sources.REWARD;
    }
}
