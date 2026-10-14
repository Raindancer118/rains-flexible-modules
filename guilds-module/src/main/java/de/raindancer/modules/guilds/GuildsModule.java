package de.raindancer.modules.guilds;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
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
import de.raindancer.modules.guilds.store.GuildMigration;
import de.raindancer.modules.guilds.store.VaultImport;
import de.raindancer.modules.guilds.store.VaultStore;
import de.raindancer.modules.guilds.store.GuildsDatabase;
import de.raindancer.modules.guilds.store.ProgressBook;
import de.raindancer.modules.guilds.service.PositionService;
import de.raindancer.modules.guilds.service.PerkService;
import de.raindancer.modules.guilds.listener.PerkListener;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.model.Activity;
import de.raindancer.modules.guilds.model.XpSource;
import de.raindancer.modules.guilds.rules.ActivityRule;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.world.blocks.PlacedBlocks;
import de.raindancer.modules.guilds.util.PermissionNodes;
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

    private static final ModuleInfo INFO = ModuleInfo.of("guilds", "Guilds", "0.9.0")
            .describedAs("Guilds founded by players: a ladder of ranks with their own permissions, a bank with daily "
                    + "limits, levels earned by playing that unlock members, allies and a guild home, alliances and "
                    + "rivals, guild, officer and ally chat, and the guild's tag in front of every member's name")
            .by("Raindancer118");

    private GuildBook book;
    private ProgressBook progress;

    /** Blocks whose "did a player put this here?" matters: ores and the melons and pumpkins the world grows. */
    private static java.util.Set<org.bukkit.Material> placedMatters() {
        java.util.Set<org.bukkit.Material> watched = java.util.EnumSet.copyOf(ActivityRule.ores());
        watched.addAll(ActivityRule.GROWN_BLOCKS);
        return watched;
    }

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

        var databases = context.core().databases();
        // This server's own database first: it is where a 1.x guilds.db is brought in, and what the guilds of a
        // server that ran before the network kept. The network's one database is the one the guilds live in.
        Database own = databases.of(GuildsDatabase.NAME, GuildsDatabase.SCHEMA);
        Database database = databases.shared(GuildsDatabase.NAME, GuildsDatabase.SCHEMA);
        boolean network = context.core().network().isNetwork();
        String serverId = network ? context.core().network().serverId() : "";
        if (own != database) {
            GuildMigration.Report moved = GuildMigration.run(own, database, serverId,
                    problem -> context.log().warn("guilds: {}", problem), System::currentTimeMillis);
            if (moved.failed()) {
                context.log().error("This server's own guilds could not all be brought into the network's database; "
                        + "they are tried again at the next start.");
            } else if (moved.brought() > 0 || moved.left() > 0) {
                context.log().info("Guilds of this server in the network's database now: {} brought, {} left where they were.",
                        moved.brought(), moved.left());
            }
        }
        book = new GuildBook(database, problem -> context.log().warn("guilds: {}", problem));
        GuildBook loading = book;
        offTheServerThread("load", loading::load);
        context.closeWith(book.connect(context.core().bus()));
        if (!book.readable()) {
            context.log().error("The guilds database could not be read. Every guild is held as it is and no change is "
                    + "accepted until it can be — saving now would replace everybody's guilds with nobody's.");
        }
        int vaults = VaultImport.run(database, book.all(),
                owner -> new YamlStore(context.dataFolder().resolve("vaults").resolve(owner + ".yml")), serverId,
                problem -> context.log().warn("guilds: {}", problem));
        if (vaults > 0) {
            context.log().info("{} guild vault(s) of this server are in the database now.", vaults);
        }

        SettingsStore<GuildProgressSettings> progressSettings = context.settings(GuildProgressSettings.class,
                GuildProgressSettings.DEFAULTS);
        progress = new ProgressBook(database);
        ProgressBook loadingProgress = progress;
        offTheServerThread("load", loadingProgress::load);
        context.closeWith(progress.connect(context.core().bus()));
        if (!progress.readable()) {
            context.log().error("The experience tables of the guilds database could not be read; contracts and the "
                    + "breakdown by source are held as they are until they can be.");
        }
        book.onDisband(loadingProgress::forget);

        GuildsSettings now = settings.current();
        GuildNotifier notifier = new GuildNotifier(server, context.core().messages(), () -> context.core().buttons());
        context.closeWith(notifier.network(context.core().bus(), context.core()::networkPlayers));
        GuildService guilds = new GuildService(book, notifier, context.log(), System::currentTimeMillis,
                player -> lastSeen(context, book, player), now);
        BankService bank = new BankService(book, notifier, context.log(), System::currentTimeMillis, BankService.current(), now);
        RelationService relations = new RelationService(book, notifier, System::currentTimeMillis, now);
        PositionService positions = new PositionService(progressSettings.current());
        XpService xp = new XpService(book, progress, notifier, context.core().effects(), System::currentTimeMillis, now,
                progressSettings.current(), positions::guildmatesNear);
        progressSettings.onChange(xp::progressSettings);
        progressSettings.onChange(positions::progressSettings);
        PerkService perks = new PerkService(book, progress, notifier,
                new VaultStore(database, de.raindancer.core.data.nbt.ItemText.ofTheServer()),
                System::currentTimeMillis, now, progressSettings.current());
        xp.afterwards(task -> Scheduling.global(context.plugin(), task));
        settings.onChange(perks::settings);
        progressSettings.onChange(perks::progressSettings);
        xp.perks(perks);
        long thousand = Math.max(1, Fees.amount("1000").minor());
        bank.onDeposit((player, amount) -> xp.activity(player, Activity.of(XpSource.DEPOSIT, amount.minor() / thousand)));
        Object watched = PlacedBlocks.watch(context.plugin(), placedMatters());
        context.closeWith(() -> PlacedBlocks.unwatch(watched));
        Travel travel = new Travel(context.plugin(), context.core().safety(), context.core().audit());
        context.closeWith(travel::clear);
        HomeService homes = new HomeService(book, notifier, server, travel, System::currentTimeMillis, now);
        homes.network(() -> serverId, context.core()::networkTravel);
        GuildChatService chat = new GuildChatService(book, notifier, now);
        context.closeWith(chat.network(context.core().bus()));
        FriendlyFireService fire = new FriendlyFireService(book);
        for (var service : List.of(notifier, guilds, bank, relations, xp, homes, chat, fire)) {
            settings.onChange(service::settings);
        }

        chat.open();
        context.closeWith(chat::close);
        de.raindancer.modules.guilds.service.GuildSidebar sidebar =
                new de.raindancer.modules.guilds.service.GuildSidebar(book, guilds, bank::format, settings::current);
        de.raindancer.core.ui.scoreboard.SidebarSections.register(
                de.raindancer.modules.guilds.service.GuildSidebar.OWNER, sidebar::lines);
        context.closeWith(() -> de.raindancer.core.ui.scoreboard.SidebarSections.unregister(
                de.raindancer.modules.guilds.service.GuildSidebar.OWNER));
        // @TAG means a guild's members in every command that takes players: /tpahere @DAWN.
        de.raindancer.core.platform.command.PlayerGroups.register("guilds", guilds::membersByTag, guilds::tags);
        context.closeWith(() -> de.raindancer.core.platform.command.PlayerGroups.unregister("guilds"));
        // A guild as a lasting group, so a claim can belong to it (/claim guild).
        de.raindancer.core.social.team.Groups.register("guilds", guilds.groups());
        context.closeWith(() -> de.raindancer.core.social.team.Groups.unregister("guilds"));
        fire.open(context.core().combat(), context.plugin().getName());
        context.closeWith(() -> fire.close(context.core().combat()));

        GuildsServices services = new GuildsServices(context.plugin(), server, context.core(), context.log(),
                context.core().messages(), context.chat().brand(), settings::current, book, notifier, guilds, bank,
                relations, xp, homes, chat, positions, perks);
        guilds.extraMembers(perks::extraMembers);
        relations.extraAllies(perks::extraAllies);
        homes.travelPercent(perks::homeTravelPercent);
        guilds.disbandGuard(guild -> {
            GuildOutcome sealed = perks.sealForDisband(guild);
            if (sealed == GuildOutcome.VAULT_UNREADABLE) {
                context.log().error("The vault of the guild {} could not be read (plugins/…/vaults). It is held as it is: "
                        + "nothing goes in and the guild cannot be disbanded until the file is fixed.", guild.name());
            }
            return sealed;
        }, perks::unseal);
        book.onDisband(perks::forgetVault);
        // Prestige stars and the weekly crown, after the tag — its own badge, so whoever paints the tag need not know.
        de.raindancer.core.ui.identity.NameBadges.register("guild-honours", player -> settings.current().tagInNames()
                ? book.guildOf(player).flatMap(perks::honours).map(text -> net.kyori.adventure.text.Component.text(text,
                        net.kyori.adventure.text.format.NamedTextColor.GOLD))
                : java.util.Optional.empty());
        context.closeWith(() -> de.raindancer.core.ui.identity.NameBadges.unregister("guild-honours"));
        context.listener(new PerkListener(services));
        ProgressListener progress = new ProgressListener(services);
        context.listener(progress);
        context.listener(new PresenceListener(services, List.<IGuildsListener>of(progress)));

        var flush = Scheduling.asyncTimer(context.plugin(), 2, 2, task -> {
            book.flush();
            loadingProgress.flush();
        });
        var perkEffects = Scheduling.globalTimer(context.plugin(), 200L, 200L, task -> {
            for (Player online : server.getOnlinePlayers()) {
                Scheduling.entity(context.plugin(), online, () -> perks.refreshEffects(online));
            }
        });
        if (perkEffects != null) {
            context.closeWith(perkEffects::cancel);
        }
        var looking = Scheduling.globalTimer(context.plugin(), 100L, 100L, task -> {
            for (Player online : server.getOnlinePlayers()) {
                Scheduling.entity(context.plugin(), online, () -> positions.saw(online.getUniqueId(),
                        online.getWorld().getName(), online.getLocation().getX(), online.getLocation().getZ()));
            }
        });
        if (looking != null) {
            context.closeWith(looking::cancel);
        }
        if (flush != null) {
            context.closeWith(flush::cancel);
        }
        AtomicInteger minutes = new AtomicInteger();
        var minute = Scheduling.globalTimer(context.plugin(), 20L * 60, 20L * 60, task -> {
            for (Player online : server.getOnlinePlayers()) {
                book.seen(online.getUniqueId(), System.currentTimeMillis());
                if (!Away.isAway(online.getUniqueId())) {
                    xp.activity(online.getUniqueId(), Activity.of(XpSource.PLAY, 1));
                }
            }
            book.expire(System.currentTimeMillis());
            perks.settle();
            if (minutes.incrementAndGet() % 60 == 0) {
                guilds.succeed();
            }
        });
        if (minute != null) {
            context.closeWith(minute::cancel);
        }
        context.closeWith(() -> offTheServerThread("save", () -> {
            loading.flush();
            loadingProgress.flush();
        }));
        GuildsCommands.ready(services);
        context.log().info("Guilds are up: {} guild(s).", book.count());
    }

    /**
     * When somebody was last on anywhere on the network: now for anybody online on any server, otherwise the
     * latest of this server's record and the guild's own (which every server keeps up for its players); empty
     * when nobody knows.
     */
    private static OptionalLong lastSeen(ModuleContext context, GuildBook book, UUID player) {
        Server server = context.plugin().getServer();
        if (server.getPlayer(player) != null
                || context.core().networkPlayers().flatMap(players -> players.where(player, true)).isPresent()) {
            return OptionalLong.of(System.currentTimeMillis());
        }
        long here = server.getOfflinePlayer(player).getLastSeen();
        long shared = book.guildOf(player).flatMap(guild -> guild.member(player)).map(Member::seen).orElse(0L);
        long seen = Math.max(here, shared);
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
        ProgressBook closingProgress = progress;
        offTheServerThread("save", () -> {
            if (closing != null) {
                closing.flush();
            }
            if (closingProgress != null) {
                closingProgress.flush();
            }
        });
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
