package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.PricedNames;
import de.raindancer.modules.economy.model.TransactionKind;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * Money for playing: mobs killed, ores mined, advancements made. All of it counts against the same hourly
 * cap as the salary, so no single farm can outrun the rest of the economy.
 */
public final class RewardService implements IEconomyService {

    private final RainEconomy economy;
    private final EarningWindow window;
    private volatile EconomySettings settings;
    private volatile PricedNames mobs = PricedNames.NONE;
    private volatile PricedNames blocks = PricedNames.NONE;

    public RewardService(RainEconomy economy, EarningWindow window, EconomySettings settings) {
        this.economy = economy;
        this.window = window;
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
        Currency currency = this.settings.currency();
        this.mobs = PricedNames.parse(this.settings.mobRewards(), currency);
        this.blocks = PricedNames.parse(this.settings.miningRewards(), currency);
    }

    /** Whether breaking this block would pay — so only those are marked when placed. */
    public boolean pays(Material material) {
        return blocks.of(material.name()).map(Money::isPositive).orElse(false);
    }

    public void killed(Player killer, EntityType type, boolean fromSpawner) {
        EconomySettings live = settings;
        if (!live.mobRewardsEnabled() || (fromSpawner && !live.spawnerMobsPay())) {
            return;
        }
        mobs.of(type.name()).ifPresent(amount -> pay(killer, amount, "Killed " + Catalogue.readable(type.name())));
    }

    /** @param placed whether a player put this block here */
    public void mined(Player miner, Material material, boolean placed) {
        if (!settings.miningRewardsEnabled() || placed) {
            return;
        }
        blocks.of(material.name()).ifPresent(amount -> pay(miner, amount, "Mined " + Catalogue.readable(material.name())));
    }

    public void advanced(Player player, String title) {
        EconomySettings live = settings;
        if (!live.advancementRewardsEnabled()) {
            return;
        }
        pay(player, live.advancementMoney(), "Advancement: " + title);
    }

    /** Pays through the hourly cap; what the cap or the account refuses is simply not paid. */
    public EconomyResult pay(Player player, Money amount, String reason) {
        return pay(player, amount, reason, TransactionKind.REWARD);
    }

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
        EconomyResult result = economy.move(player.getUniqueId(), allowed, kind, reason);
        if (!result.succeeded()) {
            window.refund(player.getUniqueId(), allowed);
        }
        return result;
    }
}
