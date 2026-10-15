package de.raindancer.modules.oneblock;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.oneblock.listener.ArrivalListener;
import de.raindancer.modules.oneblock.listener.MoneyGateListener;
import de.raindancer.modules.oneblock.listener.OneBlockListener;
import de.raindancer.modules.oneblock.listener.PortalListener;
import de.raindancer.modules.oneblock.listener.PresenceListener;
import de.raindancer.modules.oneblock.listener.SidebarListener;
import de.raindancer.modules.oneblock.model.Ranks;
import de.raindancer.modules.oneblock.rules.InviteRule;
import de.raindancer.modules.oneblock.screen.ChooseModeMenu;
import de.raindancer.modules.oneblock.service.AdminService;
import de.raindancer.modules.oneblock.service.InviteService;
import de.raindancer.modules.oneblock.service.IslandEndpoint;
import de.raindancer.modules.oneblock.service.IslandLifecycle;
import de.raindancer.modules.oneblock.service.IslandServer;
import de.raindancer.modules.oneblock.service.IslandService;
import de.raindancer.modules.oneblock.service.IslandTravel;
import de.raindancer.modules.oneblock.service.IslandWorlds;
import de.raindancer.modules.oneblock.service.IslandsGateway;
import de.raindancer.modules.oneblock.service.LocalGateway;
import de.raindancer.modules.oneblock.service.MigrationService;
import de.raindancer.modules.oneblock.service.ModeBadges;
import de.raindancer.modules.oneblock.service.NetworkDelivery;
import de.raindancer.modules.oneblock.service.OneBlockService;
import de.raindancer.modules.oneblock.service.OwnIslands;
import de.raindancer.modules.oneblock.service.RemoteGateway;
import de.raindancer.modules.oneblock.service.SidebarService;
import de.raindancer.modules.oneblock.store.ContentStore;
import de.raindancer.modules.oneblock.store.InviteBook;
import de.raindancer.modules.oneblock.store.IslandBook;
import de.raindancer.modules.oneblock.store.IslandLand;
import de.raindancer.modules.oneblock.store.OneBlockDatabase;
import de.raindancer.modules.oneblock.store.PendingCommands;
import de.raindancer.modules.oneblock.store.SqlIslandStorage;
import de.raindancer.modules.oneblock.util.PermissionNodes;
import org.bukkit.Server;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OneBlock islands across the network. The island server keeps the islands — each its own void world, with a nether
 * and an end unlocked by shards, up while somebody plays and unloaded after — and this module runs on every server
 * and carries {@code /island}: going there, and team and guest invites, between them.
 *
 * <p>Which server is the island server: on a network, the one whose id is the setting {@code proxy-server}; off one,
 * the one with {@code hosts-islands}.
 */
public final class OneBlockModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("oneblock", "OneBlock", "0.4.0")
            .describedAs("OneBlock islands, each its own world with its own nether and end, unloaded while nobody "
                    + "plays — with /island and invites from anywhere on the network.")
            .by("Raindancer118");

    private static final long SWEEP_TICKS = 100L;
    /** The sidebar redraws every two seconds; unchanged lines cost nothing (Core compares). */
    private static final long SIDEBAR_TICKS = 40L;
    private static final long LIFECYCLE_TICKS = 100L;
    private static final long AUTO_BREAK_TICKS = 20L;
    private static final long FLUSH_SECONDS = 2L;
    /** How often the other servers ask which money the islands of their players play with, for the tablist. */
    private static final long BADGE_TICKS = 300L;

    private LogChannel log;
    private IslandEndpoint endpoint;
    private IslandBook book;
    private IslandLand land;
    private ModuleContext context;
    private ModeBadges badges;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        this.context = context;
        log = context.log();
        Server server = context.plugin().getServer();
        SettingsStore<OneBlockSettings> settings = context.settings(OneBlockSettings.class, OneBlockSettings.DEFAULTS);
        context.core().messages().defineFrom(OneBlockModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(OneBlockModule.class.getResourceAsStream("messages-serious.yml"));
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }
        ProxyTransfer.register(context.plugin());

        Executor onServer = task -> Scheduling.global(context.plugin(), task);
        // On a network the link stands down: every question goes over Core's calls, and who is where is its presence.
        Optional<NetworkCalls> calls = context.core().network().isNetwork() ? context.core().networkCalls()
                : Optional.empty();
        NetworkDelivery delivery = new NetworkDelivery(context.plugin(), server, context.core().messages(),
                context.core()::links, calls.isPresent() ? context.core()::networkPlayers : Optional::empty,
                player -> context.core().vanish().isVanished(player.getUniqueId()));

        boolean hostsIslands = settings.current().hostsIslands() || (context.core().network().isNetwork()
                && context.core().network().serverId().equalsIgnoreCase(settings.current().proxyServer()));

        IslandsGateway gateway;
        Optional<InviteService> invites = Optional.empty();
        Optional<SidebarService> sidebar = Optional.empty();
        Optional<IslandServer> islandServer = Optional.empty();
        Optional<MigrationService> migration = Optional.empty();
        AtomicReference<OneBlockServices> built = new AtomicReference<>();
        if (hostsIslands) {
            IslandServer islands = islands(context, server, settings, built);
            islandServer = Optional.of(islands);
            migration = Optional.of(new MigrationService(context.plugin(), server, log, context.core().messages(), islands,
                    context.dataFolder()));
            InviteService service = new InviteService(islands.host(), new InviteBook(), new InviteRule(Ranks.MEMBER),
                    delivery, delivery::resolve, onServer, System::currentTimeMillis, settings.current());
            settings.onChange(service::settings);
            invites = Optional.of(service);
            gateway = new LocalGateway(service, islands.host(), onServer);
            List<ServerLink> links = new ArrayList<>(context.core().links().all().values());
            PendingCommands pending = islands.host().pending();
            endpoint = new IslandEndpoint(service, links, calls, (player, at) -> context.core().networkPlayers()
                    .flatMap(players -> players.where(player, true)).filter(here -> here.server().equals(at)).isPresent(),
                    pending::put);
            endpoint.listen();
            timer(context, SWEEP_TICKS, service::sweep);
            SidebarService board = new SidebarService(context.core().messages(), context.core().scoreboards());
            sidebar = Optional.of(board);
            timer(context, SIDEBAR_TICKS, () -> board.refresh(server.getOnlinePlayers(), islands.host()));
            context.closeWith(() -> server.getOnlinePlayers().forEach(online -> board.forget(online.getUniqueId())));
            log.info("This is the island server: answering invites over {}.",
                    calls.isPresent() ? "the network" : links.size() + " link(s)");
            if (links.isEmpty() && calls.isEmpty()) {
                log.warn("No links in RainsCore's link.yml — invites work only between players on this server.");
            }
        } else if (calls.isPresent()) {
            // The island server's network id is the proxy's name for it, the one setting both agree on.
            gateway = RemoteGateway.overNetwork(calls.get(), () -> settings.current().proxyServer());
        } else {
            gateway = new RemoteGateway(() -> context.core().links().named(settings.current().islandServer()));
            if (context.core().links().named(settings.current().islandServer()).isEmpty()) {
                log.warn("No link called '{}' in RainsCore's link.yml — /island still sends people to '{}', but "
                        + "invites from here are refused until there is one.", settings.current().islandServer(),
                        settings.current().proxyServer());
            }
        }

        badges(context, server, islandServer);

        OneBlockServices services = new OneBlockServices(context.plugin(), server, log, context.core().messages(),
                context.core(), settings::current, gateway, invites, sidebar, islandServer, migration);
        built.set(services);
        if (invites.isPresent()) {
            context.listener(new ArrivalListener(services));
            context.listener(new SidebarListener(services));
        }
        islandServer.ifPresent(islands -> {
            context.listener(new OneBlockListener(islands.oneBlock()));
            context.listener(new PresenceListener(context.plugin(), islands));
            context.listener(new PortalListener(context.plugin(), islands, context.core().messages()));
            context.listener(new MoneyGateListener(islands.book(), context.core().messages(), settings::current));
        });
        OneBlockCommands.ready(services);
    }

    /** The island server's own half: the book, the worlds, the block, the lifecycle — and Core's land questions. */
    private IslandServer islands(ModuleContext context, Server server, SettingsStore<OneBlockSettings> settings,
                                 AtomicReference<OneBlockServices> built) {
        var database = context.core().databases().shared(OneBlockDatabase.NAME, OneBlockDatabase.SCHEMA);
        SqlIslandStorage storage = new SqlIslandStorage(database);
        book = new IslandBook(storage);
        if (!offTheServerThread(book::load)) {
            log.error("The islands could not be read from {}. Nobody can play until they can — fix the database "
                    + "and restart; nothing was written over.", database.describe());
        } else {
            log.info("{} island(s) loaded.", book.size());
        }
        ContentStore content = new ContentStore(context.dataFolder().resolve("content.yml"), log);
        offTheServerThread(() -> {
            content.reload();
            return true;
        });
        IslandWorlds worlds = new IslandWorlds(server, log);
        Travel travel = new Travel(context.plugin(), context.core().safety(), context.core().audit());
        IslandTravel islandTravel = new IslandTravel(server, worlds, travel, context.core().messages(),
                System::currentTimeMillis, settings.current());
        settings.onChange(islandTravel::settings);
        IslandService service = new IslandService(book, content::current, System::currentTimeMillis);
        OneBlockService oneBlock = new OneBlockService(context.plugin(), server, book, content::current,
                context.core().messages(), context.core().actionBars());
        IslandLifecycle lifecycle = new IslandLifecycle(server, log, book, worlds, islandTravel,
                context.core().messages(), oneBlock::working, System::currentTimeMillis, settings.current());
        settings.onChange(lifecycle::settings);
        AdminService admin = new AdminService(book, System::currentTimeMillis,
                island -> lifecycle.tick(book.get(island.id()).orElse(island), System.currentTimeMillis()));
        AtomicReference<IslandServer> self = new AtomicReference<>();
        OwnIslands host = new OwnIslands(server, book, service, islandTravel, context.core().messages(),
                content::current, context.core().vanish()::isVanished,
                player -> new ChooseModeMenu(built.get(), self.get(), player, null, true).open(),
                player -> new ChooseModeMenu(built.get(), self.get(), player, null, false).open(),
                storage, new PendingCommands(System::currentTimeMillis),
                task -> Scheduling.async(context.plugin(), task),
                (player, task) -> Scheduling.entity(context.plugin(), player, task));
        IslandServer islands = new IslandServer(book, storage, content, worlds, islandTravel, service, oneBlock,
                lifecycle, admin, host, context.chat().brand());
        self.set(islands);

        land = new IslandLand(book);
        if (!context.core().land().provider(land)) {
            log.warn("Another plugin already answers who may build where; island protection is theirs on this server.");
            land = null;
        }
        timer(context, LIFECYCLE_TICKS, lifecycle::tick);
        timer(context, AUTO_BREAK_TICKS, oneBlock::autoBreak);
        var flushing = Scheduling.asyncTimer(context.plugin(), FLUSH_SECONDS, FLUSH_SECONDS, task -> {
            int failed = book.flush();
            if (failed > 0) {
                log.warn("{} island(s) could not be saved; trying again in {} s.", failed, FLUSH_SECONDS);
            }
        });
        if (flushing != null) {
            context.closeWith(flushing::cancel);
        }
        return islands;
    }

    /**
     * The tablist badge of a player's island money, on every server. The island server reads its book; every other
     * server reads the shared table for whoever is online, now and then, off the server thread.
     */
    private void badges(ModuleContext context, Server server, Optional<IslandServer> islandServer) {
        if (islandServer.isPresent()) {
            IslandBook islands = islandServer.get().book();
            badges = new ModeBadges(context.core().messages(),
                    player -> islands.ofPlayer(player).map(de.raindancer.modules.oneblock.model.Island::mode));
        } else {
            var storage = new SqlIslandStorage(context.core().databases().shared(OneBlockDatabase.NAME,
                    OneBlockDatabase.SCHEMA));
            badges = new ModeBadges(context.core().messages(), null);
            ModeBadges refreshing = badges;
            timer(context, BADGE_TICKS, () -> {
                List<java.util.UUID> online = server.getOnlinePlayers().stream()
                        .map(org.bukkit.entity.Player::getUniqueId).toList();
                Scheduling.async(context.plugin(), () -> refreshing.refresh(online, () -> storage.modesOf(online)));
            });
        }
        badges.register();
    }

    /**
     * Runs {@code work} on a thread of its own and waits for it: what the island server needs before anybody may play
     * is read from the network's database, which Core will not have read from the thread running the world.
     */
    private static boolean offTheServerThread(java.util.function.BooleanSupplier work) {
        boolean[] result = {false};
        Thread reader = new Thread(() -> result[0] = work.getAsBoolean(), "oneblock-load");
        reader.start();
        try {
            reader.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !reader.isAlive() && result[0];
    }

    private void timer(ModuleContext context, long ticks, Runnable task) {
        var timer = Scheduling.globalTimer(context.plugin(), ticks, ticks, running -> task.run());
        if (timer != null) {
            context.closeWith(timer::cancel);
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return OneBlockCommands.declared();
    }

    @Override
    public void disable() {
        OneBlockCommands.stopped();
        if (endpoint != null) {
            endpoint.stop();
            endpoint = null;
        }
        if (badges != null) {
            badges.unregister();
            badges = null;
        }
        if (land != null && context != null) {
            context.core().land().withdraw(land);
            land = null;
        }
        if (book != null) {
            IslandBook closing = book;
            book = null;
            // The last changes, off the server thread but waited for: Core's database refuses to be used from the
            // thread running the world, and a stop must not lose the last breaks.
            Thread writer = new Thread(closing::flush, "oneblock-final-flush");
            writer.start();
            try {
                writer.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (closing.hasUnwritten()) {
                log.error("Some island changes could not be saved before the stop.");
            }
        }
    }
}
