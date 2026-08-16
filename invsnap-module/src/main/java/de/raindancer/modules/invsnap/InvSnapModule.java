package de.raindancer.modules.invsnap;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.invsnap.listener.PlayerDeathSnapshotListener;
import de.raindancer.modules.invsnap.listener.PlayerQuitSnapshotListener;
import de.raindancer.modules.invsnap.rules.RetentionRule;
import de.raindancer.modules.invsnap.rules.SnapshotDueRule;
import de.raindancer.modules.invsnap.screen.InvSnapRootMenu;
import de.raindancer.modules.invsnap.screen.SnapshotHistoryMenu;
import de.raindancer.modules.invsnap.service.AutoSnapshotService;
import de.raindancer.modules.invsnap.service.SnapshotService;
import de.raindancer.modules.invsnap.store.SnapshotStore;
import de.raindancer.modules.invsnap.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inventory snapshots, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsInventorySnapshots}. Every online
 * player's main inventory, armour and off hand are recorded on a timer, on disconnect, and the
 * instant they die — kept in a rolling window per player. An admin picks a player, inspects,
 * compares or restores one of their snapshots through {@code /invsnap}; naming a player directly
 * with {@code /invsnap <player>} skips the picker.
 */
public final class InvSnapModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("invsnap", "Inventory Snapshots", "1.1.0")
            .describedAs("Periodic inventory snapshots for every online player, with an admin "
                    + "screen to browse a player's history and restore one.")
            .by("Raindancer118");

    /** Asked once a second — see {@code SnapshotDueRule} for why a fixed short poll beats scheduling
     *  the timer itself at the configured interval. */
    private static final long TICK_PERIOD_TICKS = 20L;

    private LogChannel log;
    private SettingsStore<InvSnapSettings> settings;
    private InvSnapServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(InvSnapSettings.class, InvSnapSettings.DEFAULTS);

        context.core().messages().defineFrom(
                InvSnapModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        SnapshotStore store = new SnapshotStore(context.dataFolder());
        SnapshotService snapshotService = new SnapshotService(log, store, new RetentionRule(),
                settings.current());
        AutoSnapshotService autoSnapshotService = new AutoSnapshotService(snapshotService,
                new SnapshotDueRule(), settings.current());

        settings.onChange(snapshotService::settings);
        settings.onChange(autoSnapshotService::settings);

        context.listener(new PlayerQuitSnapshotListener(snapshotService, autoSnapshotService));
        context.listener(new PlayerDeathSnapshotListener(snapshotService));

        services = new InvSnapServices(context.plugin(), server, log, context.core().messages(),
                context.chat().brand(), context.core(), settings::current, settings,
                snapshotService, new LiveScreens());

        InvSnapCommands.ready(services);

        var timer = Scheduling.globalTimer(context.plugin(), TICK_PERIOD_TICKS, TICK_PERIOD_TICKS,
                task -> autoSnapshotService.tick(server.getOnlinePlayers(), Instant.now()));
        if (timer != null) {
            context.closeWith(timer::cancel);
        }

        log.info("Inventory snapshots are up: every {}s, {} kept per player.",
                settings.current().snapshotInterval().toSeconds(),
                settings.current().retentionCountClamped());
    }

    /** Opening this module's screens, without the command knowing the menu classes. */
    private final class LiveScreens implements IInvSnapScreensOpener {

        @Override
        public void history(Player admin, UUID target, String targetName) {
            // null parent: this is an entry point from a command, and Core draws no Back button on
            // a parentless menu — right, since there is nowhere behind it to go back to.
            new SnapshotHistoryMenu(services, admin, target, targetName, null).open();
        }

        @Override
        public void root(Player admin) {
            new InvSnapRootMenu(services, admin).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return InvSnapCommands.declared();
    }

    @Override
    public void disable() {
        InvSnapCommands.stopped();
    }
}
