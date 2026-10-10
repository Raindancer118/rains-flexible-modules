package de.raindancer.modules.jobs;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.SaleStops;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.core.world.blocks.PlacedBlocks;
import de.raindancer.modules.jobs.listener.FishListener;
import de.raindancer.modules.jobs.listener.QuestListener;
import de.raindancer.modules.jobs.service.OrderService;
import de.raindancer.modules.jobs.service.QuestService;
import de.raindancer.modules.jobs.store.OrderBook;
import de.raindancer.modules.jobs.store.WorkCatalogue;
import de.raindancer.modules.jobs.store.QuestBook;
import de.raindancer.modules.jobs.store.QuestCatalogue;
import de.raindancer.modules.jobs.service.GoalService;
import de.raindancer.modules.jobs.store.GoalBook;
import de.raindancer.modules.jobs.store.TemplateCatalogue;
import de.raindancer.modules.jobs.util.PermissionNodes;

import java.security.SecureRandom;
import java.util.List;

/**
 * The job board: goals the whole server works on, paid by share. Money goes through Core's {@code Economies};
 * the shop is told what not to sell through Core's {@code SaleStops} — no dependency on the economy module.
 */
public final class JobsModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("jobs", "Jobs", "0.6.0")
            .describedAs("The job board — goals everybody delivers or fishes towards, paid by their share — personal daily quests, harder and better paid the richer you are, and orders: name what you want to earn and race the clock")
            .by("Raindancer118");

    private GoalService goals;
    private QuestService quests;
    private OrderService orders;
    private volatile Object watched;

    /** Watches the blocks the quests now ask for — after quests.yml is read again. */
    public static void rewatch(QuestService quests, org.bukkit.plugin.Plugin plugin) {
        JobsModule running = current;
        if (running != null) {
            PlacedBlocks.unwatch(running.watched);
            running.watched = PlacedBlocks.watch(plugin, mined(quests, running.orders));
        }
    }

    private static volatile JobsModule current;

    private static java.util.Set<org.bukkit.Material> mined(QuestService quests, OrderService orders) {
        java.util.Set<org.bukkit.Material> blocks = java.util.EnumSet.noneOf(org.bukkit.Material.class);
        blocks.addAll(quests.minedBlocks());
        if (orders != null) {
            blocks.addAll(orders.minedBlocks());
        }
        return blocks;
    }

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        current = this;
        SettingsStore<JobsSettings> settings = context.settings(JobsSettings.class, JobsSettings.DEFAULTS);
        context.core().messages().defineFrom(JobsModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(JobsModule.class.getResourceAsStream("messages-serious.yml"));
        int registered = PermissionNodes.register(context.plugin().getServer());
        if (registered > 0) {
            context.log().info("{} permission(s) registered.", registered);
        }

        TemplateCatalogue templates = new TemplateCatalogue(new YamlStore(context.dataFolder().resolve("jobs.yml")),
                () -> JobsModule.class.getResourceAsStream("jobs.yml"));
        int kinds = templates.reload();
        templates.problems().forEach(problem -> context.log().warn("jobs.yml: {}", problem));
        GoalBook book = new GoalBook(new YamlStore(context.dataFolder().resolve("goals.yml")));
        book.load();
        if (!book.readable()) {
            context.log().error("goals.yml could not be read. The board is held as it is until it is fixed — "
                    + "saving now would lose every goal and everything handed in.");
        }

        goals = new GoalService(context.plugin().getServer(), templates, book, context.core().messages(),
                context.log(), System::currentTimeMillis, new SecureRandom(), settings.current());
        settings.onChange(goals::settings);
        SettingsStore<QuestSettings> questSettings = context.settings(QuestSettings.class, QuestSettings.DEFAULTS);
        QuestCatalogue questCatalogue = new QuestCatalogue(new YamlStore(context.dataFolder().resolve("quests.yml")),
                () -> JobsModule.class.getResourceAsStream("quests.yml"));
        int questKinds = questCatalogue.reload();
        questCatalogue.problems().forEach(problem -> context.log().warn("quests.yml: {}", problem));
        QuestBook questBook = new QuestBook(new YamlStore(context.dataFolder().resolve("quest-progress.yml")));
        questBook.load();
        if (!questBook.readable()) {
            context.log().error("quest-progress.yml could not be read. Quests are counted but nothing is saved or paid "
                    + "until it is fixed — saving now would lose everybody's progress.");
        }
        quests = new QuestService(context.plugin().getServer(), questCatalogue, questBook, context.core().messages(),
                context.log(), System::currentTimeMillis, java.time.ZoneId.systemDefault(), new SecureRandom(),
                questSettings.current());
        questSettings.onChange(quests::settings);
        SettingsStore<OrderSettings> orderSettings = context.settings(OrderSettings.class, OrderSettings.DEFAULTS);
        WorkCatalogue work = new WorkCatalogue(new YamlStore(context.dataFolder().resolve("orders.yml")),
                () -> JobsModule.class.getResourceAsStream("orders.yml"));
        int workKinds = work.reload();
        work.problems().forEach(problem -> context.log().warn("orders.yml: {}", problem));
        OrderBook orderBook = new OrderBook(new YamlStore(context.dataFolder().resolve("order-progress.yml")));
        orderBook.load();
        if (!orderBook.readable()) {
            context.log().error("order-progress.yml could not be read. Nobody can take an order until it is fixed.");
        }
        orders = new OrderService(context.plugin().getServer(), work, orderBook, context.core().messages(),
                context.log(), System::currentTimeMillis, java.time.ZoneId.systemDefault(), new SecureRandom(),
                orderSettings.current());
        orderSettings.onChange(orders::settings);
        JobsServices services = new JobsServices(context.plugin(), context.plugin().getServer(), context.core(),
                context.log(), context.core().messages(), context.chat().brand(), settings::current, goals, quests,
                orders);
        // Mining must not count blocks a player put there; Core remembers where the ones quests and orders ask for are placed.
        watched = PlacedBlocks.watch(context.plugin(), mined(quests, orders));
        context.closeWith(() -> PlacedBlocks.unwatch(watched));
        QuestListener questListener = new QuestListener(services);
        context.listener(questListener);
        var travel = Scheduling.globalTimer(context.plugin(), 20L * 10, 20L * 10, task -> {
            for (org.bukkit.entity.Player online : context.plugin().getServer().getOnlinePlayers()) {
                Scheduling.entity(context.plugin(), online, () -> questListener.sample(online));
            }
        });
        if (travel != null) {
            context.closeWith(travel::cancel);
        }
        // The order's clock, once a second, for whoever has one running.
        var clocks = Scheduling.globalTimer(context.plugin(), 20L, 20L, task -> {
            for (org.bukkit.entity.Player online : context.plugin().getServer().getOnlinePlayers()) {
                if (orders.current(online.getUniqueId()).isPresent()) {
                    Scheduling.entity(context.plugin(), online, () -> orders.bar(online));
                }
            }
        });
        if (clocks != null) {
            context.closeWith(clocks::cancel);
        }

        SaleStops.provide(context.plugin(), goals);
        context.closeWith(() -> SaleStops.retract(goals));
        context.listener(new FishListener(services));
        var minutes = Scheduling.globalTimer(context.plugin(), 20L * 5, 20L * 60, task -> {
            goals.tick();
            quests.tick();
            orders.tick();
        });
        if (minutes != null) {
            context.closeWith(minutes::cancel);
        }
        context.closeWith(goals::flush);
        context.closeWith(quests::flush);
        context.closeWith(orders::flush);
        JobsCommands.ready(services);
        context.log().info("The job board is up: {} kind(s) of goal, {} on the board; {} personal quest(s), {} kind(s) "
                + "of work for orders.", kinds, book.active().size(), questKinds, workKinds);
    }

    @Override
    public List<ModuleCommand> commands() {
        return JobsCommands.declared();
    }

    @Override
    public void disable() {
        JobsCommands.stopped();
        if (goals != null) {
            goals.flush();
        }
        if (quests != null) {
            quests.flush();
        }
        if (orders != null) {
            orders.flush();
        }
        current = null;
    }
}
