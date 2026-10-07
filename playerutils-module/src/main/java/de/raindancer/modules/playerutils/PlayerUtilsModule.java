package de.raindancer.modules.playerutils;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.profile.ProfileMenu;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.playerutils.listener.FlightListener;
import de.raindancer.modules.playerutils.listener.SpectateListener;
import de.raindancer.modules.playerutils.rules.ArgumentRule;
import de.raindancer.modules.playerutils.rules.FlightRule;
import de.raindancer.modules.playerutils.rules.NearRule;
import de.raindancer.modules.playerutils.rules.PingRule;
import de.raindancer.modules.playerutils.rules.SpeedRule;
import de.raindancer.modules.playerutils.rules.SudoRule;
import de.raindancer.modules.playerutils.rules.TargetRule;
import de.raindancer.modules.playerutils.rules.WipeRule;
import de.raindancer.modules.playerutils.screen.EffectsMenu;
import de.raindancer.modules.playerutils.screen.PlayerToolsMenu;
import de.raindancer.modules.playerutils.service.ActionService;
import de.raindancer.modules.playerutils.service.FlightService;
import de.raindancer.modules.playerutils.service.InfoService;
import de.raindancer.modules.playerutils.service.SpectateService;
import de.raindancer.modules.playerutils.service.SudoService;
import de.raindancer.modules.playerutils.service.Targeting;
import de.raindancer.modules.playerutils.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public final class PlayerUtilsModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("playerutils", "Player Utils", "0.1.2")
            .describedAs("Fly, heal, feed, hurt, drown, launch, scale, speed, wipe, spectate and sudo — and "
                    + "ping, status, hunger, effects, position and who is near. By name, nickname or selector.")
            .by("Raindancer118");

    private LogChannel log;
    private PlayerUtilsServices services;
    private SpectateService spectate;
    private Server server;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        server = context.plugin().getServer();
        SettingsStore<PlayerUtilsSettings> settings =
                context.settings(PlayerUtilsSettings.class, PlayerUtilsSettings.DEFAULTS);
        context.core().messages().defineFrom(
                PlayerUtilsModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        PlayerUtilsSettings now = settings.current();
        var messages = context.core().messages();
        TargetRule targetRule = new TargetRule();
        SudoRule sudoRule = new SudoRule();
        PingRule pings = new PingRule();
        NearRule nearRule = new NearRule();
        FlightRule flightRule = new FlightRule();
        WipeRule wipeRule = new WipeRule();
        SpeedRule speedRule = new SpeedRule();

        Targeting targeting = new Targeting(server, messages, targetRule, now);
        FlightService flight = new FlightService(context.plugin(), context.core(), flightRule, now);
        spectate = new SpectateService(context.plugin(), server, messages, now);
        SudoService sudo = new SudoService(context.plugin(), server, context.core(), messages, sudoRule, now);
        InfoService info = new InfoService(context.plugin(), context.core(), messages, pings, nearRule,
                speedRule, now);
        ActionService actions = new ActionService(context.plugin(), context.core(), messages, targeting, flight,
                spectate, sudo, wipeRule, speedRule, now);
        for (var service : List.of(targeting, flight, spectate, sudo, info, actions)) {
            settings.onChange(service::settings);
        }

        services = new PlayerUtilsServices(context.plugin(), server, log, messages, context.chat().brand(),
                context.core(), settings::current, settings, new ArgumentRule(), targetRule, sudoRule, pings,
                nearRule, flightRule, wipeRule, speedRule, targeting, actions, flight, spectate, sudo, info,
                new LiveScreens());

        context.listener(new FlightListener(services));
        context.listener(new SpectateListener(services));
        PlayerUtilsCommands.ready(services);

        // A reload with players online: put back flight that was given before it.
        for (Player online : server.getOnlinePlayers()) {
            de.raindancer.core.platform.util.Scheduling.onOwner(context.plugin(), online,
                    () -> flight.restore(online));
        }
        log.info("Player utils are up: {} commands, flight {}, sudo {}.",
                PlayerUtilsCommands.declared().size(), now.flightPersists() ? "kept" : "not kept",
                now.sudoEnabled() ? "on" : "off");
    }

    private final class LiveScreens implements IPlayerUtilsScreensOpener {

        @Override
        public void tools(Player viewer, UUID target) {
            new PlayerToolsMenu(services, viewer, null, target).open();
        }

        @Override
        public void choose(Player viewer) {
            new PlayerChooser(viewer, services.brand(), null, "Whose tools?", List.of(), picked -> {
                if (picked.online() && viewer.hasPermission(PermissionNodes.TOOLS)) {
                    new PlayerToolsMenu(services, viewer, null, picked.id()).open();
                } else {
                    new ProfileMenu(viewer, services.brand(), null, picked.id(), picked.name()).open();
                }
            }).open();
        }

        @Override
        public void effects(Player viewer, UUID target) {
            new EffectsMenu(services, viewer, null, target).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return PlayerUtilsCommands.declared();
    }

    @Override
    public void disable() {
        // Nobody stays a ghost because the plugin stopped under them: the way back is on them, and is
        // used here rather than at their next join.
        if (server != null && spectate != null) {
            for (Player online : server.getOnlinePlayers()) {
                if (spectate.isSpectating(online)) {
                    spectate.stop(online);
                }
            }
        }
        PlayerUtilsCommands.stopped();
    }
}
