package de.raindancer.modules.economy;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.profile.ProfileButton;
import de.raindancer.core.ui.profile.ProfileExtension;
import de.raindancer.core.ui.profile.ProfileExtensions;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.economy.listener.AccountListener;
import de.raindancer.modules.economy.listener.CashListener;
import de.raindancer.modules.economy.listener.RewardListener;
import de.raindancer.modules.economy.rules.BalanceRule;
import de.raindancer.modules.economy.rules.MarketRule;
import de.raindancer.modules.economy.screen.AdminMenu;
import de.raindancer.modules.economy.screen.BaltopMenu;
import de.raindancer.modules.economy.screen.BankMenu;
import de.raindancer.modules.economy.screen.CasinoMenu;
import de.raindancer.modules.economy.screen.CoinFlipMenu;
import de.raindancer.modules.economy.screen.DiceMenu;
import de.raindancer.modules.economy.screen.HistoryMenu;
import de.raindancer.modules.economy.screen.SellMenu;
import de.raindancer.modules.economy.screen.ShopItemsMenu;
import de.raindancer.modules.economy.screen.ShopMenu;
import de.raindancer.modules.economy.screen.SlotsMenu;
import de.raindancer.modules.economy.screen.TradeMenu;
import de.raindancer.modules.economy.screen.WithdrawMenu;
import de.raindancer.modules.economy.service.BalanceNotifier;
import de.raindancer.modules.economy.service.BillService;
import de.raindancer.modules.economy.service.CashService;
import de.raindancer.modules.economy.service.DailyService;
import de.raindancer.modules.economy.service.EarningWindow;
import de.raindancer.modules.economy.service.GamblingService;
import de.raindancer.modules.economy.service.InterestService;
import de.raindancer.modules.economy.service.LeaderboardService;
import de.raindancer.modules.economy.service.LotteryService;
import de.raindancer.modules.economy.service.PaymentService;
import de.raindancer.modules.economy.service.RainEconomy;
import de.raindancer.modules.economy.service.RewardService;
import de.raindancer.modules.economy.service.SalaryService;
import de.raindancer.modules.economy.service.ShopService;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.BasePrices;
import de.raindancer.modules.economy.store.EconomyDatabase;
import de.raindancer.modules.economy.store.MarketBook;
import de.raindancer.modules.economy.store.PriceBook;
import de.raindancer.modules.economy.util.Mini;
import de.raindancer.modules.economy.util.PermissionNodes;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * A server's money. Shipped through the standard wrapper this is {@code RainsEconomy}.
 *
 * <p>Accounts and a bank; paying and billing; coins, numbered banknotes and cheques you can carry; a shop
 * sorted like the creative inventory, priced from the server's own recipes, with supply and demand;
 * earning from mobs, mining, playing, advancements, a daily streak and interest; and a casino with coin
 * flips, dice, slots and a lottery. Every part has its own switch.
 *
 * <p>Provides RainsCore's economy, so every other plugin — and through Core's bridge every Vault plugin —
 * charges through the same accounts.
 */
public final class EconomyModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("economy", "Economy", "0.1.1")
            .describedAs("A bank, paying, coins and banknotes, a creative-style shop priced from recipes, "
                    + "ways to earn, and a casino — every part switchable.")
            .by("Raindancer118");

    /** How often what changed is written to the database. A crash loses at most this much. */
    private static final long FLUSH_SECONDS = 2;

    private LogChannel log;
    private AccountBook book;
    private MarketBook market;
    private EconomyServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        SettingsStore<EconomySettings> settings = context.settings(EconomySettings.class, EconomySettings.DEFAULTS);

        context.core().messages().defineFrom(EconomyModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(EconomyModule.class.getResourceAsStream("messages-serious.yml"));
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        Database database = context.core().databases().of(EconomyDatabase.NAME, EconomyDatabase.SCHEMA);
        book = new AccountBook(database, new BalanceRule(), System::currentTimeMillis);
        market = new MarketBook(database, new MarketRule(), System::currentTimeMillis);
        offTheServerThread(() -> {
            book.load();
            market.load();
        });
        if (!book.isLoaded()) {
            log.error("The accounts could not be read from economy.db. Every payment will be refused as "
                    + "unavailable until this is fixed — nothing is overwritten.");
        }

        byte[] basePrices = read(EconomyModule.class.getResourceAsStream(BasePrices.RESOURCE));
        PriceBook prices = new PriceBook(currency -> BasePrices.parse(new ByteArrayInputStream(basePrices), currency),
                material -> market.pressure(material, settings.current().recoveryHoursClamped()),
                material -> {
                    Material found = Material.matchMaterial(material);
                    return found == null ? 64 : found.getMaxStackSize();
                });

        EconomySettings now = settings.current();
        RainEconomy economy = new RainEconomy(book, id -> server.getOfflinePlayer(id).getName(), now);
        BalanceNotifier notifier = new BalanceNotifier(context.plugin(), server, context.core().actionBars(), now);
        economy.watch(notifier);
        EarningWindow window = new EarningWindow(System::currentTimeMillis);
        var messages = context.core().messages();
        var effects = context.core().effects();
        var buttons = context.core().buttons();

        PaymentService payments = new PaymentService(context.plugin(), economy, messages, effects, buttons, now);
        BillService bills = new BillService(context.plugin(), server, economy, messages, effects, buttons,
                System::currentTimeMillis, now);
        CashService cash = new CashService(server, economy, messages, effects, context.core().audit(), now);
        ShopService shop = new ShopService(server, economy, prices, market, messages, effects, settings, now);
        RewardService rewards = new RewardService(economy, window, now);
        SalaryService salary = new SalaryService(context.plugin(), rewards, messages, System::currentTimeMillis, now);
        InterestService interest = new InterestService(economy, messages, System::currentTimeMillis, now);
        DailyService daily = new DailyService(economy, messages, effects, Clock.systemDefaultZone(), now);
        LeaderboardService leaderboard = new LeaderboardService(book, System::currentTimeMillis);
        GamblingService gambling = new GamblingService(context.plugin(), server, economy, messages, effects, buttons,
                Clock.systemDefaultZone(), now);
        LotteryService lottery = new LotteryService(server, economy, messages, effects, System::currentTimeMillis, now);

        for (var service : List.of(economy, notifier, payments, bills, cash, shop, rewards, salary, interest, daily,
                gambling, lottery)) {
            settings.onChange(service::settings);
        }

        services = new EconomyServices(context.plugin(), server, log, messages, context.chat().brand(), context.core(),
                settings::current, settings, economy, market, payments, bills, cash, shop, rewards, salary, interest,
                daily, leaderboard, gambling, lottery, new LiveScreens());

        int recipes = shop.reprice();

        context.listener(new AccountListener(services));
        context.listener(new CashListener(services));
        context.listener(new RewardListener(services));
        for (Player online : server.getOnlinePlayers()) {
            economy.open(online.getUniqueId(), online.getName());
        }

        ProfileExtension profile = this::profileButton;
        ProfileExtensions.register(profile);
        context.closeWith(() -> ProfileExtensions.unregister(profile));

        Economies.provide(context.plugin(), economy);
        context.closeWith(() -> Economies.retract(economy));

        var flushing = Scheduling.asyncTimer(context.plugin(), FLUSH_SECONDS, FLUSH_SECONDS, task -> {
            book.flush();
            market.flush();
        });
        if (flushing != null) {
            context.closeWith(flushing::cancel);
        }
        var minutes = Scheduling.globalTimer(context.plugin(), 1200L, 1200L, task -> {
            salary.minute(server.getOnlinePlayers());
            interest.minute(server.getOnlinePlayers());
            lottery.minute();
            window.sweep();
        });
        if (minutes != null) {
            context.closeWith(minutes::cancel);
        }
        var pruning = Scheduling.asyncTimer(context.plugin(), 60, 6 * 3600, task -> {
            long cutoff = System.currentTimeMillis() - settings.current().historyDays() * 86_400_000L;
            int forgotten = book.forgetHistoryBefore(cutoff);
            if (forgotten > 0) {
                log.info("{} statement line(s) older than {} days forgotten.", forgotten, settings.current().historyDays());
            }
        });
        if (pruning != null) {
            context.closeWith(pruning::cancel);
        }

        EconomyCommands.ready(services);
        log.info("The economy is up: {} account(s), {} item(s) priced from {} recipe(s), money is {}.",
                book.all().size(), prices.priced().size(), recipes, economy.currency().format(economy.currency().ofMajor(1)));
    }

    private ProfileButton profileButton(Player viewer, org.bukkit.OfflinePlayer subject, de.raindancer.core.ui.menu.Menu parent) {
        EconomyServices live = services;
        if (live == null) {
            return null;
        }
        boolean self = viewer.getUniqueId().equals(subject.getUniqueId());
        if (!self && !viewer.hasPermission(PermissionNodes.BALANCE_OTHERS)) {
            return null;
        }
        String balance = Mini.of(live.currency().render(live.economy().balance(subject.getUniqueId())));
        if (self) {
            return new ProfileButton(Icons.of(Material.GOLD_INGOT, "<yellow>Bank", "<gray>" + balance,
                    "<yellow>Click<gray> to open"), click -> live.screens().bank(viewer));
        }
        return new ProfileButton(Icons.of(Material.GOLD_INGOT, "<yellow>Balance", "<gray>" + balance,
                live.config().payEnabled() ? "<yellow>Click<gray> to pay them" : ""), click -> {
            if (live.config().payEnabled() && viewer.hasPermission(PermissionNodes.PAY)) {
                viewer.closeInventory();
                live.messages().send(viewer, "economy.pay.how", "player", subject.getName() == null ? "" : subject.getName());
            }
        });
    }

    private static byte[] read(InputStream in) {
        if (in == null) {
            return new byte[0];
        }
        try (in) {
            return in.readAllBytes();
        } catch (IOException unreadable) {
            return new byte[0];
        }
    }

    /**
     * Runs work that touches the database on a thread of its own and waits for it. Only for the start and
     * the end, when nobody is playing yet or any more, and the alternative — the server thread — would be
     * reported as a stall.
     */
    private static void offTheServerThread(Runnable work) {
        Thread worker = Thread.ofPlatform().name("rainseconomy-io").start(work);
        try {
            worker.join(30_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return EconomyCommands.declared();
    }

    /** Stopping writes everything still waiting before anything else goes. */
    @Override
    public void disable() {
        EconomyCommands.stopped();
        if (book != null) {
            offTheServerThread(() -> {
                int written = book.flush();
                if (market != null) {
                    market.flush();
                }
                if (written < 0) {
                    log.error("The last changes to the accounts could not be written to economy.db.");
                }
            });
        }
    }

    /** Opening this module's screens, without the commands knowing the menu classes. */
    private final class LiveScreens implements IEconomyScreensOpener {

        @Override
        public void bank(Player viewer) {
            new BankMenu(services, viewer).open();
        }

        @Override
        public void shop(Player viewer) {
            new ShopMenu(services, viewer, null).open();
        }

        @Override
        public void shopCategory(Player viewer, Category category) {
            ShopItemsMenu.of(services, viewer, new ShopMenu(services, viewer, null), category).open();
        }

        @Override
        public void shopSearch(Player viewer, String text) {
            ShopItemsMenu.search(services, viewer, new ShopMenu(services, viewer, null), text).open();
        }

        @Override
        public void trade(Player viewer, Material material) {
            new TradeMenu(services, viewer, new ShopMenu(services, viewer, null), material).open();
        }

        @Override
        public void sell(Player viewer) {
            new SellMenu(services, viewer, null).open();
        }

        @Override
        public void baltop(Player viewer) {
            new BaltopMenu(services, viewer, null).open();
        }

        @Override
        public void history(Player viewer, UUID whose, String name) {
            Scheduling.async(services.plugin(), () -> {
                var lines = book.history(whose, 360, 0);
                Scheduling.entity(services.plugin(), viewer, () ->
                        new HistoryMenu(services, viewer, null, name, lines).open());
            });
        }

        @Override
        public void withdraw(Player viewer) {
            new WithdrawMenu(services, viewer, null).open();
        }

        @Override
        public void casino(Player viewer) {
            new CasinoMenu(services, viewer, null).open();
        }

        @Override
        public void slots(Player viewer) {
            SlotsMenu.open(services, viewer, null);
        }

        @Override
        public void coinflip(Player viewer, de.raindancer.core.social.economy.Money stake, Boolean call) {
            CoinFlipMenu.open(services, viewer, null, stake, call);
        }

        @Override
        public void dice(Player viewer, de.raindancer.core.social.economy.Money stake, Boolean over, Integer target) {
            DiceMenu.open(services, viewer, null, stake, over, target);
        }

        @Override
        public void admin(Player viewer) {
            new AdminMenu(services, viewer, null).open();
        }
    }
}
