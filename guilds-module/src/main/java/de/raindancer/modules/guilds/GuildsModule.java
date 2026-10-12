package de.raindancer.modules.guilds;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.presence.Away;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.guilds.listener.IGuildsListener;
import de.raindancer.modules.guilds.listener.PresenceListener;
import de.raindancer.modules.guilds.listener.ProgressListener;
import de.raindancer.modules.guilds.service.BankService;
import de.raindancer.modules.guilds.service.FriendlyFireService;
import de.raindancer.modules.guilds.service.GuildChatService;
import de.raindancer.modules.guilds.service.GuildNotifier;
import de.raindancer.modules.guilds.service.GuildService;
import de.raindancer.modules.guilds.service.HomeService;
import de.raindancer.modules.guilds.service.RelationService;
import de.raindancer.modules.guilds.service.XpService;
import de.raindancer.modules.guilds.store.GuildBook;
import de.raindancer.modules.guilds.store.GuildsDatabase;
import de.raindancer.modules.guilds.store.SqliteGuildStorage;
import de.raindancer.modules.guilds.util.PermissionNodes;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guilds. Membership and names live in Core's team roster, money moves through Core's economy, combat refusals
 * through Core's {@code Combat}, the tag through Core's {@code NameBadges}, chat through Core's channels and the
 * home through Core's {@code Travel} — what is here is only what a guild is.
 */
public final class GuildsModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("guilds", "Guilds", "0.2.0")
            .describedAs("Guilds founded by players: a ladder of ranks with their own permissions, a bank with daily "
                    + "limits, levels earned by playing that unlock members, allies and a guild home, alliances and "
                    + "rivals, guild, officer and ally chat, and the guild's tag in front of every member's name")
            .by("Raindancer118");

    private GuildBook book;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        SettingsStore<GuildsSettings> settings = context.settings(GuildsSettings.class, GuildsSettings.DEFAULTS);
        context.core().messages().defineFrom(GuildsModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(GuildsModule.class.getResourceAsStream("messages-serious.yml"));
        Server server = context.plugin().getServer();
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            context.log().info("{} permission(s) registered.", registered);
        }

        var database = context.core().databases().of(GuildsDatabase.NAME, GuildsDatabase.SCHEMA);
        book = new GuildBook(new SqliteGuildStorage(database, problem -> context.log().warn("guilds.db: {}", problem)));
        GuildBook loading = book;
        offTheServerThread("load", () -> loading.load().forEach(problem -> context.log().warn("guilds.db: {}", problem)));
        if (!book.readable()) {
            context.log().error("guilds.db could not be read. Every guild is held as it is and no change is accepted "
                    + "until it is fixed — saving now would replace everybody's guilds with nobody's.");
        }

        GuildsSettings now = settings.current();
        GuildNotifier notifier = new GuildNotifier(server, context.core().messages(), () -> context.core().buttons());
        GuildService guilds = new GuildService(book, notifier, context.log(), System::currentTimeMillis,
                player -> lastSeen(server, player), now);
        BankService bank = new BankService(book, notifier, context.log(), System::currentTimeMillis, BankService.current(), now);
        RelationService relations = new RelationService(book, notifier, System::currentTimeMillis, now);
        XpService xp = new XpService(book, notifier, context.core().effects(), System::currentTimeMillis, now);
        Travel travel = new Travel(context.plugin(), context.core().safety(), context.core().audit());
        context.closeWith(travel::clear);
        HomeService homes = new HomeService(book, notifier, server, travel, System::currentTimeMillis, now);
        GuildChatService chat = new GuildChatService(book, notifier, now);
        FriendlyFireService fire = new FriendlyFireService(book);
        for (var service : List.of(notifier, guilds, bank, relations, xp, homes, chat, fire)) {
            settings.onChange(service::settings);
        }

        chat.open();
        context.closeWith(chat::close);
        fire.open(context.core().combat(), context.plugin().getName());
        context.closeWith(() -> fire.close(context.core().combat()));

        GuildsServices services = new GuildsServices(context.plugin(), server, context.core(), context.log(),
                context.core().messages(), context.chat().brand(), settings::current, book, notifier, guilds, bank,
                relations, xp, homes, chat);
        ProgressListener progress = new ProgressListener(services);
        context.listener(progress);
        context.listener(new PresenceListener(services, List.<IGuildsListener>of(progress)));

        var flush = Scheduling.asyncTimer(context.plugin(), 2, 2, task -> book.flush());
        if (flush != null) {
            context.closeWith(flush::cancel);
        }
        AtomicInteger minutes = new AtomicInteger();
        var minute = Scheduling.globalTimer(context.plugin(), 20L * 60, 20L * 60, task -> {
            int perMinute = settings.current().xpPerMinute();
            if (perMinute > 0) {
                for (Player online : server.getOnlinePlayers()) {
                    if (!Away.isAway(online.getUniqueId())) {
                        xp.earn(online.getUniqueId(), perMinute);
                    }
                }
            }
            book.expire(System.currentTimeMillis());
            if (minutes.incrementAndGet() % 60 == 0) {
                guilds.succeed();
            }
        });
        if (minute != null) {
            context.closeWith(minute::cancel);
        }
        context.closeWith(() -> offTheServerThread("save", loading::flush));
        GuildsCommands.ready(services);
        context.log().info("Guilds are up: {} guild(s).", book.count());
    }

    /** When somebody was last on: now for anybody online, Paper's record otherwise; empty when it has none. */
    private static OptionalLong lastSeen(Server server, UUID player) {
        if (server.getPlayer(player) != null) {
            return OptionalLong.of(System.currentTimeMillis());
        }
        OfflinePlayer known = server.getOfflinePlayer(player);
        long seen = known.getLastSeen();
        return seen > 0 ? OptionalLong.of(seen) : OptionalLong.empty();
    }

    @Override
    public List<ModuleCommand> commands() {
        return GuildsCommands.declared();
    }

    @Override
    public void disable() {
        GuildsCommands.stopped();
        GuildBook closing = book;
        if (closing != null) {
            offTheServerThread("save", closing::flush);
        }
    }

    /**
     * Runs database work on a thread of its own and waits for it. Only for start and stop, where the server
     * has to wait anyway — but a read on the thread running the world is what Core flags, rightly, since
     * the same habit anywhere else is a stall every player feels.
     */
    private static void offTheServerThread(String what, Runnable work) {
        Thread worker = new Thread(work, "RainsGuilds " + what);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
