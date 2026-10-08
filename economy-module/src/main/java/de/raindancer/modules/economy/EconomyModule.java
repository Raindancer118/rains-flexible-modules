package de.raindancer.modules.economy;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.ItemValues;
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
import de.raindancer.modules.economy.screen.JobsMenu;
import de.raindancer.modules.economy.screen.RouletteMenu;
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
import de.raindancer.modules.economy.service.IncomeService;
import de.raindancer.modules.economy.service.HireService;
import de.raindancer.modules.economy.service.StatementService;
import de.raindancer.modules.economy.service.GameSounds;
import de.raindancer.modules.economy.service.ShopService;
import de.raindancer.modules.economy.store.AccountBook;
import de.raindancer.modules.economy.store.BasePrices;
import de.raindancer.modules.economy.store.EconomyDatabase;
import de.raindancer.modules.economy.store.MarketBook;
import de.raindancer.modules.economy.store.CashTags;
import de.raindancer.modules.economy.store.PriceBook;
import de.raindancer.modules.economy.service.ShopItemValuer;
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
 * <p>Accounts and a bank with statements as a real book; paying, billing and hiring; sealed coins and
 * cheques you can carry; a shop sorted like the creative inventory, priced from the server's own recipes,
 * with supply and demand and enchanted items worth more; passive income, advancements, a daily streak and
 * interest; and a casino — coin flips, dice, slots, roulette and a lottery — animated and with sounds.
 * Every part has its own switch.
 *
 * <p>Provides RainsCore's economy, so every other plugin — and through Core's bridge every Vault plugin —
 * charges through the same accounts.
 */
public final class EconomyModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("economy", "Economy", "0.15.0")
            .describedAs("A bank, paying and hiring, coins you can carry, a creative-style shop priced from recipes, "
                    + "passive income, live auctions and raffles, and a casino with sounds and animations — every part switchable.")
            .by("Raindancer118");

    /** How often what changed is written to the database. A crash loses at most this much. */
    private static final long FLUSH_SECONDS = 2;

    private LogChannel log;
    private AccountBook book;
    private MarketBook market;
    private EconomyServices services;
    private de.raindancer.modules.economy.service.TableService tables;
    private de.raindancer.modules.economy.service.CrashService crash;
    private de.raindancer.modules.economy.service.RaceService race;

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
        CashService cash = new CashService(server, economy, messages, effects, context.core().audit(),
                de.raindancer.modules.economy.store.CashSeal.load(context.dataFolder().resolve("cash.key")), now);
        ShopService shop = new ShopService(server, economy, prices, market, messages, effects, settings, now);
        RewardService rewards = new RewardService(economy, window, now);
        IncomeService income = new IncomeService(context.plugin(), rewards, messages, System::currentTimeMillis, now);
        HireService hire = new HireService(context.plugin(), server, economy, messages, effects, buttons,
                System::currentTimeMillis, now);
        StatementService statements = new StatementService(context.plugin(), server, economy, now);
        GameSounds sounds = new GameSounds(effects);
        InterestService interest = new InterestService(economy, messages, System::currentTimeMillis, now);
        DailyService daily = new DailyService(economy, messages, effects, Clock.systemDefaultZone(), now);
        LeaderboardService leaderboard = new LeaderboardService(book, System::currentTimeMillis);
        var sidebar = new de.raindancer.modules.economy.service.SidebarService(economy, leaderboard,
                context.core().scoreboards(), now);
        var displays = new de.raindancer.modules.economy.service.LeaderboardDisplayService(context.plugin(), server,
                leaderboard, settings, now);
        GamblingService gambling = new GamblingService(context.plugin(), server, economy, messages, effects, buttons,
                Clock.systemDefaultZone(), sounds, now);
        LotteryService lottery = new LotteryService(context.plugin(), server, economy, messages, effects,
                System::currentTimeMillis, now);
        var tables = new de.raindancer.modules.economy.service.TableService(server, gambling, now);
        var scratch = new de.raindancer.modules.economy.service.ScratchService(economy, gambling, cash.seal(), messages, now);
        var crash = new de.raindancer.modules.economy.service.CrashService(server, gambling, System::currentTimeMillis, now);
        var race = new de.raindancer.modules.economy.service.RaceService(server, gambling, System::currentTimeMillis, now);
        var dealers = new de.raindancer.modules.economy.service.DealerService(now);
        var auctions = new de.raindancer.modules.economy.service.AuctionService(context.plugin(), server, economy,
                messages, effects, buttons, context.core().bossBars(), sounds, System::currentTimeMillis, now);
        var raffles = new de.raindancer.modules.economy.service.RaffleService(context.plugin(), server, economy,
                messages, effects, buttons, sounds, auctions, System::currentTimeMillis, now);
        var experience = new de.raindancer.modules.economy.service.ExperienceService(economy, messages, effects, now);
        var tax = new de.raindancer.modules.economy.service.WealthTaxService(context.plugin(), server, economy, messages,
                System::currentTimeMillis, now);

        var loans = new de.raindancer.modules.economy.service.LoanService(server, economy, messages, effects,
                System::currentTimeMillis, now);
        gambling.overdue(loans::overdue);
        lottery.overdue(loans::overdue);

        for (var service : List.of(economy, notifier, payments, bills, cash, shop, rewards, income, hire, statements,
                interest, daily, gambling, lottery, sidebar, displays, tables, scratch, crash, race, dealers, auctions,
                raffles, tax, experience, loans)) {
            settings.onChange(service::settings);
        }

        services = new EconomyServices(context.plugin(), server, log, messages, context.chat().brand(), context.core(),
                settings::current, settings, economy, market, payments, bills, cash, shop, rewards, income, hire,
                statements, interest, daily, leaderboard, sidebar, displays, gambling, lottery, tables, scratch, crash,
                race, dealers, auctions, raffles, tax, experience, loans, new LiveScreens());
        sidebar.pot(lottery::pot);
        this.tables = tables;
        this.crash = crash;
        this.race = race;

        int recipes = shop.reprice();

        context.listener(new AccountListener(services));
        context.listener(new CashListener(services));
        context.listener(new RewardListener(services));
        context.listener(new de.raindancer.modules.economy.listener.DealerListener(services));
        var rounds = Scheduling.globalTimer(context.plugin(), 2L, 2L, task -> {
            crash.tick();
            race.tick();
        });
        if (rounds != null) {
            context.closeWith(rounds::cancel);
        }
        auctions.resume();
        var hammer = Scheduling.globalTimer(context.plugin(), 20L, 20L, task -> {
            auctions.tick();
            raffles.tick();
        });
        if (hammer != null) {
            context.closeWith(hammer::cancel);
        }
        for (Player online : server.getOnlinePlayers()) {
            economy.open(online.getUniqueId(), online.getName());
        }

        ProfileExtension profile = this::profileButton;
        ProfileExtensions.register(profile);
        context.closeWith(() -> ProfileExtensions.unregister(profile));

        Economies.provide(context.plugin(), economy);
        context.closeWith(() -> Economies.retract(economy));
        ShopItemValuer valuer = new ShopItemValuer(prices::tag, CashTags::isCash, ShopItemValuer::materialName);
        ItemValues.provide(context.plugin(), valuer);
        context.closeWith(() -> ItemValues.retract(valuer));

        var flushing = Scheduling.asyncTimer(context.plugin(), FLUSH_SECONDS, FLUSH_SECONDS, task -> {
            book.flush();
            market.flush();
        });
        if (flushing != null) {
            context.closeWith(flushing::cancel);
        }
        var minutes = Scheduling.globalTimer(context.plugin(), 1200L, 1200L, task -> {
            income.minute(server.getOnlinePlayers());
            hire.minute();
            interest.minute(server.getOnlinePlayers());
            lottery.minute();
            tax.minute();
            loans.minute();
            window.sweep();
        });
        if (minutes != null) {
            context.closeWith(minutes::cancel);
        }
        var sidebars = Scheduling.globalTimer(context.plugin(), 40L, 40L, task -> sidebar.refresh(server.getOnlinePlayers()));
        if (sidebars != null) {
            context.closeWith(sidebars::cancel);
        }
        var boards = Scheduling.globalTimer(context.plugin(), 100L, 600L, task -> displays.refresh());
        if (boards != null) {
            context.closeWith(boards::cancel);
        }
        context.closeWith(() -> server.getOnlinePlayers().forEach(player -> sidebar.forget(player.getUniqueId())));
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
        // Nobody's stake is lost to a stop: open hands are finished in the player's favour, open rounds refunded.
        if (tables != null) {
            tables.leaveAll();
        }
        if (crash != null) {
            crash.refundAll();
        }
        if (race != null) {
            race.refundAll();
        }
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

        @Override
        public void table(Player viewer, de.raindancer.modules.economy.model.DealerGame game) {
            switch (game) {
                case BLACKJACK -> de.raindancer.modules.economy.screen.BlackjackMenu.open(services, viewer, null);
                case BACCARAT -> de.raindancer.modules.economy.screen.BaccaratMenu.open(services, viewer, null);
                case HILO -> de.raindancer.modules.economy.screen.HiLoMenu.open(services, viewer, null);
                case ROULETTE -> RouletteMenu.open(services, viewer, null, null);
                case SLOTS -> SlotsMenu.open(services, viewer, null);
                case MINES -> de.raindancer.modules.economy.screen.MinesMenu.open(services, viewer, null);
                case CRASH -> de.raindancer.modules.economy.screen.CrashMenu.open(services, viewer, null);
                case RACE -> de.raindancer.modules.economy.screen.RaceMenu.open(services, viewer, null);
                case LOTTERY -> new de.raindancer.modules.economy.screen.LotteryMenu(services, viewer, null).open();
                case CASINO -> new CasinoMenu(services, viewer, null).open();
            }
        }

        @Override
        public void scratch(Player viewer, de.raindancer.modules.economy.service.ScratchService.Scratched card) {
            new de.raindancer.modules.economy.screen.ScratchMenu(services, viewer, card).open();
        }

        @Override
        public void jobs(Player viewer) {
            new JobsMenu(services, viewer, null).open();
        }

        @Override
        public void loan(Player viewer) {
            new de.raindancer.modules.economy.screen.LoanMenu(services, viewer, new BankMenu(services, viewer)).open();
        }

        @Override
        public void roulette(Player viewer, de.raindancer.core.social.economy.Money stake) {
            RouletteMenu.open(services, viewer, null, stake);
        }

        @Override
        public void auctions(Player viewer) {
            de.raindancer.modules.economy.screen.AuctionMenu.open(services, viewer, null);
        }

        @Override
        public void raffles(Player viewer) {
            de.raindancer.modules.economy.screen.RaffleMenu.open(services, viewer, null);
        }
    }
}
