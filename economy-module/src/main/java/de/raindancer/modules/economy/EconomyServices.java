package de.raindancer.modules.economy;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.service.BillService;
import de.raindancer.modules.economy.service.CashService;
import de.raindancer.modules.economy.service.DailyService;
import de.raindancer.modules.economy.service.GamblingService;
import de.raindancer.modules.economy.service.InterestService;
import de.raindancer.modules.economy.service.LeaderboardService;
import de.raindancer.modules.economy.service.LotteryService;
import de.raindancer.modules.economy.service.PaymentService;
import de.raindancer.modules.economy.service.RainEconomy;
import de.raindancer.modules.economy.service.RewardService;
import de.raindancer.modules.economy.service.IncomeService;
import de.raindancer.modules.economy.service.HireService;
import de.raindancer.modules.economy.service.StatementService;
import de.raindancer.modules.economy.service.ShopService;
import de.raindancer.modules.economy.store.MarketBook;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** Everything this module built, in one place to hand around. Owns nothing; only carries. */
public record EconomyServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,
        RainsCore core,

        Supplier<EconomySettings> settings,
        SettingsStore<EconomySettings> store,

        RainEconomy economy,
        MarketBook market,
        PaymentService payments,
        BillService bills,
        CashService cash,
        ShopService shop,
        RewardService rewards,
        IncomeService income,
        HireService hire,
        StatementService statements,
        InterestService interest,
        DailyService daily,
        LeaderboardService leaderboard,
        de.raindancer.modules.economy.service.SidebarService sidebar,
        de.raindancer.modules.economy.service.LeaderboardDisplayService displays,
        GamblingService gambling,
        LotteryService lottery,
        IEconomyScreensOpener screens) {

    public EconomySettings config() {
        return settings.get();
    }

    public Currency currency() {
        return economy.currency();
    }

    public Effects effects() {
        return core.effects();
    }
}
