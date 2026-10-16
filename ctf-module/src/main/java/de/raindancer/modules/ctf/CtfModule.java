package de.raindancer.modules.ctf;

import de.raindancer.core.data.loadout.LoadoutStore;
import de.raindancer.core.data.loadout.Loadouts;
import de.raindancer.core.data.runs.RunHistory;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.hologram.FloatingBoards;
import de.raindancer.core.world.movement.JumpPads;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.ctf.listener.LobbyListener;
import de.raindancer.modules.ctf.listener.PresenceListener;
import de.raindancer.modules.ctf.listener.RoundListener;
import de.raindancer.modules.ctf.rules.CombatRule;
import de.raindancer.modules.ctf.screen.AdminMenu;
import de.raindancer.modules.ctf.screen.ChallengesMenu;
import de.raindancer.modules.ctf.screen.ClassMenu;
import de.raindancer.modules.ctf.screen.CtfMenu;
import de.raindancer.modules.ctf.screen.LooksMenu;
import de.raindancer.modules.ctf.screen.SetupMenu;
import de.raindancer.modules.ctf.screen.SideMenu;
import de.raindancer.modules.ctf.screen.StatsMenu;
import de.raindancer.modules.ctf.screen.TopMenu;
import de.raindancer.modules.ctf.screen.VoteMenu;
import de.raindancer.modules.ctf.service.AnnouncerService;
import de.raindancer.modules.ctf.service.ArenaWorldService;
import de.raindancer.modules.ctf.service.FlagService;
import de.raindancer.modules.ctf.service.LobbyService;
import de.raindancer.modules.ctf.service.MapSetupService;
import de.raindancer.modules.ctf.service.MutatorService;
import de.raindancer.modules.ctf.service.PartyService;
import de.raindancer.modules.ctf.service.PowerUpService;
import de.raindancer.modules.ctf.service.ProgressService;
import de.raindancer.modules.ctf.service.RoleService;
import de.raindancer.modules.ctf.service.RoundService;
import de.raindancer.modules.ctf.service.SpectatorService;
import de.raindancer.modules.ctf.service.SupplyService;
import de.raindancer.modules.ctf.service.VoteService;
import de.raindancer.modules.ctf.store.CtfDatabase;
import de.raindancer.modules.ctf.store.LobbyStore;
import de.raindancer.modules.ctf.store.MapStore;
import de.raindancer.modules.ctf.store.ProfileStore;
import de.raindancer.modules.ctf.store.StatsStore;
import de.raindancer.modules.ctf.util.PermissionNodes;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Capture the Flag: maps built from schematics into a world of their own, a lobby with votes, parkour and a throwing
 * range, rounds of classic, one-flag, neutral-flag and guild-war Capture the Flag with classes, power-ups, supply
 * drops, jump pads, a control point and twists, an announcer, awards, levels, daily challenges and leaderboards.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsCaptureTheFlag}. It runs on a server of its own
 * ({@code lobby-on-join}) or beside anything else, where {@code /ctf join} keeps what a player carried until they
 * leave.
 */
public final class CtfModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("ctf", "Capture the Flag", "0.1.0")
            .describedAs("Capture the Flag with classes, power-ups, twists, votes, awards, levels and challenges")
            .by("Raindancer118");

    private LogChannel log;
    private RoundService rounds;
    private PowerUpService powerUps;
    private FloatingBoards boards;
    private ArenaWorldService arena;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        SettingsStore<CtfSettings> settings = context.settings(CtfSettings.class, CtfSettings.DEFAULTS);
        CtfSettings now = settings.current();
        context.core().messages().defineFrom(CtfModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(CtfModule.class.getResourceAsStream("messages-serious.yml"));
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        Path mapsFolder = context.dataFolder().resolve("maps");
        try {
            Files.createDirectories(mapsFolder);
        } catch (IOException failure) {
            log.error("The maps folder could not be made: {}", failure.getMessage());
        }
        MapStore maps = new MapStore(mapsFolder, write -> Scheduling.async(context.plugin(), write));
        maps.refresh();
        arena = new ArenaWorldService(context.plugin(), log, mapsFolder, context.dataFolder().resolve("built.yml"), now);
        MapSetupService setup = new MapSetupService(context.plugin(), maps, arena, now);

        var database = context.core().databases().of(CtfDatabase.NAME, CtfDatabase.SCHEMA);
        StatsStore stats = new StatsStore(database);
        ProfileStore profiles = new ProfileStore(database);
        RunHistory runs = context.core().runHistory("ctf");

        LobbyService lobby = new LobbyService(context.plugin(), new LobbyStore(context.dataFolder().resolve("lobby.yml")),
                new LoadoutStore(context.dataFolder().resolve("kept")), Loadouts.ofTheServer(), runs,
                System::currentTimeMillis, now);
        FlagService flags = new FlagService(context.plugin(), context.core().effects(), now);
        RoleService roles = new RoleService(context.plugin(), context.core().effects(), System::currentTimeMillis, now);
        powerUps = new PowerUpService(context.plugin(), context.core().effects(), roles, System::currentTimeMillis, now);
        SupplyService supplies = new SupplyService(context.plugin(), context.core().effects(), powerUps, now);
        MutatorService mutators = new MutatorService(context.plugin(), now);
        AnnouncerService announcer = new AnnouncerService(context.plugin(), context.core().messages(),
                context.core().effects(), now);
        ProgressService progress = new ProgressService(context.plugin(), log, context.core().messages(),
                context.core().effects(), stats, profiles, context.core().achievements(), now);
        progress.defineAchievements();
        PartyService parties = new PartyService(System::currentTimeMillis);
        VoteService votes = new VoteService(now);
        SpectatorService spectators = new SpectatorService(context.plugin(), now);

        rounds = new RoundService(context.plugin(), log, context.core().messages(), context.core().effects(),
                context.core().scoreboards(), context.core().bossBars(), context.core().actionBars(), maps, arena,
                lobby, flags, roles, powerUps, supplies, mutators, announcer, progress, parties, votes, spectators,
                System::currentTimeMillis, now);
        arena.building(rounds::mayBuildAtAll);

        boards = new FloatingBoards(context.plugin(), server, context.dataFolder().resolve("boards.yml"), "board",
                () -> MiniMessage.miniMessage().deserialize(boardText(stats)), 30);

        settings.onChange(fresh -> {
            arena.settings(fresh);
            setup.settings(fresh);
            lobby.settings(fresh);
            flags.settings(fresh);
            roles.settings(fresh);
            powerUps.settings(fresh);
            supplies.settings(fresh);
            mutators.settings(fresh);
            announcer.settings(fresh);
            progress.settings(fresh);
            parties.settings(fresh);
            votes.settings(fresh);
            spectators.settings(fresh);
            rounds.settings(fresh);
        });

        CtfServices[] built = new CtfServices[1];
        ICtfScreensOpener screens = new ICtfScreensOpener() {
            @Override
            public void main(Player viewer) {
                new CtfMenu(built[0], viewer).open();
            }

            @Override
            public void vote(Player viewer) {
                new VoteMenu(built[0], viewer, new CtfMenu(built[0], viewer)).open();
            }

            @Override
            public void classes(Player viewer) {
                new ClassMenu(built[0], viewer, new CtfMenu(built[0], viewer)).open();
            }

            @Override
            public void sides(Player viewer) {
                new SideMenu(built[0], viewer, new CtfMenu(built[0], viewer)).open();
            }

            @Override
            public void stats(Player viewer, UUID whose) {
                StatsMenu.open(built[0], viewer, new CtfMenu(built[0], viewer), whose);
            }

            @Override
            public void top(Player viewer) {
                TopMenu.open(built[0], viewer, new CtfMenu(built[0], viewer));
            }

            @Override
            public void looks(Player viewer) {
                new LooksMenu(built[0], viewer, new CtfMenu(built[0], viewer)).open();
            }

            @Override
            public void challenges(Player viewer) {
                new ChallengesMenu(built[0], viewer, new CtfMenu(built[0], viewer)).open();
            }

            @Override
            public void admin(Player viewer) {
                new AdminMenu(built[0], viewer, new CtfMenu(built[0], viewer)).open();
            }

            @Override
            public void setup(Player viewer) {
                new SetupMenu(built[0], viewer, new AdminMenu(built[0], viewer, null)).open();
            }
        };
        CtfServices services = new CtfServices(context.plugin(), server, log, context.core().messages(),
                context.chat().brand(), context.core(), settings::current, maps, arena, setup, lobby, rounds, flags,
                roles, announcer, progress, parties, votes, spectators, stats, boards, screens);
        built[0] = services;

        CombatRule combat = new CombatRule();
        context.core().combat().alsoAsk(context.plugin().getName(), attack -> combat.verdict(attack,
                arena::isArenaWorld, rounds::sideOf, settings.current().friendlyFire()));

        context.listener(rounds.rewind());
        context.listener(new PresenceListener(services));
        context.listener(new RoundListener(services));
        context.listener(new LobbyListener(services));
        context.listener(new JumpPads(context.plugin(), context.core().effects(), player ->
                settings.current().jumpPads() && (rounds.isIn(player.getUniqueId())
                        || lobby.isInside(player.getUniqueId())), 1.6, 1.1));

        Scheduling.global(context.plugin(), () -> {
            arena.world();
            boards.start();
            powerUps.start();
            log.info("Capture the Flag is up: {} map(s), {} playable.", maps.all().size(),
                    maps.all().stream().filter(map -> map.isPlayable()).count());
        });
        var slow = Scheduling.globalTimer(context.plugin(), 20L, 20L, task -> rounds.tick());
        var fast = Scheduling.globalTimer(context.plugin(), 4L, 4L, task -> rounds.fastTick());
        if (slow != null) {
            context.closeWith(slow::cancel);
        }
        if (fast != null) {
            context.closeWith(fast::cancel);
        }
        CtfCommands.ready(services);
        if (now.lobbyOnJoin()) {
            for (Player online : server.getOnlinePlayers()) {
                Scheduling.entity(context.plugin(), online, () -> rounds.join(online));
            }
        }
    }

    private static String boardText(StatsStore stats) {
        StringBuilder out = new StringBuilder("<red><bold>⚑ CAPTURE THE FLAG ⚑</bold></red>\n<gray>Most captures\n");
        List<de.raindancer.modules.ctf.model.Stats> best = stats.top(StatsStore.Board.CAPTURES, 5);
        if (best.isEmpty()) {
            out.append("<gray>Nobody yet — be the first!\n");
        }
        String[] places = {"<gold>①", "<gray>②", "<#cd7f32>③", "<white>④", "<white>⑤"};
        for (int index = 0; index < best.size(); index++) {
            out.append(places[index]).append(" <white>").append(best.get(index).name()).append(" <gray>— <yellow>")
                    .append(best.get(index).captures()).append("\n");
        }
        List<StatsStore.Weekly> week = stats.week(LocalDate.now().toEpochDay() / 7, 3);
        if (!week.isEmpty()) {
            out.append("\n<aqua>This week\n");
            for (int index = 0; index < week.size(); index++) {
                out.append(places[index]).append(" <white>").append(week.get(index).name()).append(" <gray>— <yellow>")
                        .append(week.get(index).captures()).append("\n");
            }
        }
        return out.toString().stripTrailing();
    }

    @Override
    public List<ModuleCommand> commands() {
        return CtfCommands.declared();
    }

    @Override
    public void disable() {
        CtfCommands.stopped();
        if (rounds != null) {
            rounds.shutdown();
        }
        if (powerUps != null) {
            powerUps.stop();
        }
        if (boards != null) {
            boards.stop();
        }
        if (arena != null) {
            arena.unlock();
        }
    }
}
