package de.raindancer.modules.arena;

import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.arena.listener.MatchListener;
import de.raindancer.modules.arena.listener.NpcListener;
import de.raindancer.modules.arena.listener.PresenceListener;
import de.raindancer.modules.arena.listener.ProtectionListener;
import de.raindancer.modules.arena.listener.SafetyListener;
import de.raindancer.modules.arena.listener.WaveListener;
import de.raindancer.modules.arena.model.NpcRole;
import de.raindancer.modules.arena.rules.FightRule;
import de.raindancer.modules.arena.rules.ItemUseRule;
import de.raindancer.modules.arena.rules.ProtectionRule;
import de.raindancer.modules.arena.screen.ArenaMenu;
import de.raindancer.modules.arena.screen.KitPickMenu;
import de.raindancer.modules.arena.screen.KitPreviewMenu;
import de.raindancer.modules.arena.screen.ModeMenu;
import de.raindancer.modules.arena.screen.StatsMenu;
import de.raindancer.modules.arena.screen.WavesMenu;
import de.raindancer.modules.arena.service.AgreedFights;
import de.raindancer.modules.arena.service.ArenaLink;
import de.raindancer.modules.arena.service.ArenaService;
import de.raindancer.modules.arena.service.ArenaWorldService;
import de.raindancer.modules.arena.service.IntentBook;
import de.raindancer.modules.arena.service.InviteService;
import de.raindancer.modules.arena.service.KitDefaults;
import de.raindancer.modules.arena.service.KitService;
import de.raindancer.modules.arena.service.MatchService;
import de.raindancer.modules.arena.service.NetworkService;
import de.raindancer.modules.arena.service.NpcService;
import de.raindancer.modules.arena.service.PitSweeper;
import de.raindancer.modules.arena.service.QueueService;
import de.raindancer.modules.arena.service.StakeService;
import de.raindancer.modules.arena.service.WaveService;
import de.raindancer.modules.arena.store.KitBook;
import de.raindancer.modules.arena.store.LayoutBook;
import de.raindancer.modules.arena.store.LayoutStore;
import de.raindancer.modules.arena.store.SettlementBook;
import de.raindancer.modules.arena.store.StatsDatabase;
import de.raindancer.modules.arena.store.StatsStore;
import de.raindancer.modules.arena.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The arena server: arena worlds built from schematics, a hub with gladiators, pits to fight in, duels, sumo, teams,
 * free for all and mob waves, kits or a copy of one's own gear, stakes held on the survival server — on a server of its
 * own behind the network's proxy, so nothing anybody owns on survival is ever at stake here.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsArena}. It belongs on the arena server only; the survival
 * server runs the gate that sends people here.
 */
public final class ArenaModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("arena", "Arena", "0.2.4")
            .describedAs("A PvP arena server: duels, teams, free for all, sumo and mob waves, with kits or own gear.")
            .by("Raindancer118");

    private LogChannel log;
    private MatchService matches;
    private QueueService queue;
    private WaveService waves;
    private ArenaLink link;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        SettingsStore<ArenaSettings> settings = context.settings(ArenaSettings.class, ArenaSettings.DEFAULTS);

        context.core().messages().defineFrom(ArenaModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(ArenaModule.class.getResourceAsStream("messages-serious.yml"));

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }
        ProxyTransfer.register(context.plugin());

        Path maps = context.dataFolder().resolve("maps");
        try {
            Files.createDirectories(maps);
        } catch (IOException failure) {
            log.error("The maps folder could not be made: {}", failure.getMessage());
        }

        LayoutBook layout = new LayoutBook(new LayoutStore(context.dataFolder().resolve("layout.yml")));
        ArenaWorldService worlds = new ArenaWorldService(context.plugin(), log, maps, settings.current());
        ArenaService arena = new ArenaService(context.plugin(), server, context.core().messages(),
                context.core().effects(), worlds, layout, settings.current());

        KitService kits = new KitService(new KitBook(context.dataFolder().resolve("kits.yml")), ItemText.ofTheServer());
        kits.seed(new KitDefaults(ItemText.ofTheServer()).all());

        var database = context.core().databases().of(StatsDatabase.NAME, StatsDatabase.SCHEMA);
        StatsStore stats = new StatsStore(database);

        NpcService npcs = new NpcService(context.plugin(), role -> settings.current().npcSkin());
        link = new ArenaLink(context.core()::link);
        arena.returning(player -> link.tell(de.raindancer.modules.arena.api.ArenaProtocol.RETURNING,
                java.util.Map.of("player", player.toString())));
        StakeService stakes = new StakeService(link,
                new SettlementBook(context.dataFolder().resolve("settlements.yml")));

        matches = new MatchService(context.plugin(), server, context.core().messages(), context.core().effects(), arena,
                kits, stakes, link, stats, settings.current());
        waves = new WaveService(context.plugin(), server, matches);
        matches.waves(waves);
        PitSweeper sweeper = new PitSweeper(context.plugin(), server, entity -> npcs.role(entity).isPresent());
        matches.sweeper(sweeper);
        queue = new QueueService(context.plugin(), server, context.core().messages(), context.core().actionBars(), arena,
                matches, System::currentTimeMillis, settings.current(), id -> kits.get(id).isPresent(), link::isUp);
        matches.afterFight(queue::tick);
        queue.consent((player, spec) -> {
            if (spec.stake() > 0 || spec.isOwnGear()) {
                link.tell(de.raindancer.modules.arena.api.ArenaProtocol.CONSENT, java.util.Map.of(
                        "player", player.toString(), "stake", String.valueOf(spec.stake()),
                        "gear", String.valueOf(spec.isOwnGear())));
            }
        });

        AgreedFights agreed = new AgreedFights(System::currentTimeMillis);
        IntentBook intents = new IntentBook(System::currentTimeMillis);
        InviteService invites = new InviteService(server, context.core().messages(), link, queue, agreed,
                matches::inFight, id -> kits.get(id).isPresent(), System::currentTimeMillis,
                settings.current().mostStake());
        new NetworkService(link, intents, invites, agreed).listen();

        settings.onChange(fresh -> {
            worlds.settings(fresh);
            arena.settings(fresh);
            matches.settings(fresh);
            queue.settings(fresh);
            invites.mostStake(fresh.mostStake());
            Scheduling.global(context.plugin(), () -> worlds.loadAll(layout.current()));
        });

        ArenaServices[] built = new ArenaServices[1];
        IArenaScreensOpener screens = new IArenaScreensOpener() {
            @Override
            public void main(Player viewer) {
                new ArenaMenu(built[0], viewer).open();
            }

            @Override
            public void forRole(Player viewer, NpcRole role) {
                ArenaServices live = built[0];
                switch (role) {
                    case LANISTA -> new ModeMenu(live, viewer, new ArenaMenu(live, viewer)).open();
                    case BEASTMASTER -> new WavesMenu(live, viewer, new ArenaMenu(live, viewer)).open();
                    case ARMORER -> new KitPickMenu(live, viewer, new ArenaMenu(live, viewer), false, kit ->
                            live.kits().get(kit).ifPresent(found ->
                                    new KitPreviewMenu(live, viewer, null, found).open())).open();
                    case HERALD -> new StatsMenu(live, viewer, new ArenaMenu(live, viewer)).open();
                    case GATEKEEPER -> {
                        live.matches().left(viewer.getUniqueId());
                        live.queue().leave(viewer.getUniqueId());
                        live.arena().leave(viewer);
                    }
                }
            }
        };
        ArenaServices services = new ArenaServices(context.plugin(), server, log, context.core().messages(),
                context.chat().brand(), context.core(), settings::current, arena, matches, queue, invites, kits, waves,
                npcs, stats, link, intents, new ProtectionRule(), new ItemUseRule(), screens);
        built[0] = services;

        FightRule fights = new FightRule();
        context.core().combat().alsoAsk(context.plugin().getName(), attack ->
                fights.verdict(attack, arena::isArenaWorld, matches::opponents));

        Scheduling.global(context.plugin(), () -> {
            worlds.loadAll(layout.current());
            log.info("Arena is up: {} world(s), {} pit(s), {} kit(s), hub {}.", layout.current().worlds().size(),
                    layout.current().pits().size(), kits.all().size(),
                    layout.current().hub().isPresent() ? "set" : "not set");
        });
        matches.start();
        waves.start();
        queue.start();
        var housekeeping = Scheduling.globalTimer(context.plugin(), 1200L, 1200L, task -> {
            stakes.retry();
            intents.sweep();
            agreed.sweep();
            // The safety net: every pit nobody fights in is cleared once a minute, whatever got there.
            java.util.Set<String> busy = matches.busyPits();
            layout.current().pits().stream().filter(pit -> !busy.contains(pit.id())).forEach(sweeper::sweep);
        });
        if (housekeeping != null) {
            context.closeWith(housekeeping::cancel);
        }

        context.listener(new PresenceListener(services));
        context.listener(new ProtectionListener(services));
        context.listener(new SafetyListener(services));
        context.listener(new MatchListener(services));
        context.listener(new WaveListener(services));
        context.listener(new NpcListener(services));
        ArenaCommands.ready(services);
    }

    @Override
    public List<ModuleCommand> commands() {
        return ArenaCommands.declared();
    }

    @Override
    public void disable() {
        ArenaCommands.stopped();
        if (queue != null) {
            queue.stop();
        }
        if (waves != null) {
            waves.stop();
        }
        if (matches != null) {
            matches.stop();
        }
        if (link != null) {
            link.close();
        }
    }
}
