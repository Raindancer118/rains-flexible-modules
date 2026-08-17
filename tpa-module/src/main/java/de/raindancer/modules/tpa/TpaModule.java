package de.raindancer.modules.tpa;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.profile.ProfileExtension;
import de.raindancer.core.ui.profile.ProfileExtensions;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelListener;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.tpa.listener.TpaSessionListener;
import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.profile.RequestTeleportProfileExtension;
import de.raindancer.modules.tpa.rules.TpaAskingRule;
import de.raindancer.modules.tpa.screen.BlockedMenu;
import de.raindancer.modules.tpa.screen.RequestsMenu;
import de.raindancer.modules.tpa.screen.TpaHubMenu;
import de.raindancer.modules.tpa.screen.WhoToAskMenu;
import de.raindancer.modules.tpa.service.BackService;
import de.raindancer.modules.tpa.service.TpaPrefsService;
import de.raindancer.modules.tpa.service.TpaRequestService;
import de.raindancer.modules.tpa.store.TpaPrefsFile;
import de.raindancer.modules.tpa.store.TpaRequests;
import de.raindancer.modules.tpa.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Teleport requests, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsTPA}, a plugin of its own. Hosted inside
 * another plugin it is one feature among several, and the code below cannot tell which.
 *
 * <h2>What is deliberately not here any more</h2>
 * The standing still, the cancelling on movement and damage, finding somewhere safe to land and the
 * teleport itself are Core's {@code Travel} — and this plugin is one of the two that class was made
 * from. Its own copy was identical to the homes plugin's, down to the helper that decides whether
 * somebody has moved, and the two were fixed separately for years. The waits are Core's
 * {@code Cooldowns}; the atomic write behind the block list is Core's {@code YamlStore}; the menu, the
 * buttons, the wording and the settings are Core's.
 *
 * <h2>What {@code /back} gained by moving</h2>
 * The waypoints are Core's {@link de.raindancer.core.world.teleport.Returns} now, recorded by the one
 * class that performs every arrival. So {@code /back} undoes a warp, a home or a request alike —
 * where before it lived here and only remembered this plugin's own teleports, which meant going home
 * and then typing {@code /back} took somebody to wherever their last <em>request</em> had been from.
 */
public final class TpaModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("tpa", "Teleport requests", "2.1.1")
            .describedAs("Ask somebody whether you may come to them, or whether they will come to "
                    + "you — and go back to where you were")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<TpaSettings> settings;

    private TpaRequests requests;
    private TpaPrefsFile prefsFile;
    private Travel travel;
    private TpaRequestService asking;
    private TpaPrefsService prefs;
    private BackService back;

    private TpaServices services;
    private ProfileExtension teleportProfileExtension;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(TpaSettings.class, TpaSettings.DEFAULTS);

        // The module's own wording, offered as a floor below anything the owner has written. Not
        // Messages.load: there is one Messages on the server and it is Core's, so loading would throw
        // away Core's own lines and every other module's with them.
        //
        // Looked up beside this class rather than at "/messages.yml": RainsCore ships one at the root
        // of its own jar and join-classpath puts it on this module's classpath.
        //
        // Signed with this module's own brand, so its sentences say what they came from rather than
        // whichever module plugin happened to start last.
        context.core().messages().defineFrom(
                TpaModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        // Before anything asks. An unregistered permission resolves to "operators only", which would
        // refuse teleport requests to every ordinary player on the server.
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        // Read as it stands from the old plugin's own file, so nobody's block list quietly empties on
        // the day they update.
        prefsFile = new TpaPrefsFile(context.dataFolder().resolve(TpaPrefsFile.FILE_NAME));
        prefsFile.load();
        for (String problem : prefsFile.problems()) {
            log.warn("{}", problem);
        }
        if (!prefsFile.isReadable()) {
            // Said as loudly as a log line can be said. Nothing will be written until it is fixed —
            // which is right, since writing would replace everybody's block list with an empty one —
            // but the symptom without this line is settings that quietly never save.
            log.warn("{} could not be read, so nobody's block list or toggle is loaded AND nothing "
                            + "will be saved until it is fixed. Move it aside to start fresh.",
                    prefsFile.file());
        }

        requests = new TpaRequests();
        travel = new Travel(context.plugin(), context.core().safety(), context.core().audit());
        prefs = new TpaPrefsService(prefsFile, requests, context.core().messages(),
                settings.current());
        asking = new TpaRequestService(context.plugin(), requests, prefs, new TpaAskingRule(),
                travel, context.core().messages(), context.core().buttons(), context.core().vanish(),
                settings.current());
        back = new BackService(travel, context.core().messages(), settings.current());

        services = new TpaServices(context.plugin(), server, context.core(), log,
                context.core().messages(), context.chat(), context.chat().brand(),
                settings::current,
                requests, new TpaAskingRule(), travel, asking, prefs, back,
                new LiveScreens());

        // Every setting is a snapshot, so a reload hands each service a fresh one. Missing one of these
        // is a subsystem that keeps yesterday's numbers until the next restart, which is the sort of
        // defect reported as "the config does not work".
        settings.onChange(fresh -> {
            asking.settings(fresh);
            prefs.settings(fresh);
            back.settings(fresh);
        });

        // Core's, not a second copy of "stand still or it is cancelled". Whether being hurt counts is
        // the owner's decision, so it is read at registration.
        context.listener(new TravelListener(travel, settings.current().cancelOnDamage()));
        context.listener(new TpaSessionListener(services));

        // "Request a teleport" on Core's ProfileMenu — direct call, not a ServicesManager lookup:
        // this module already depends on Core, unlike the claims/mannequin pairing that pattern is for.
        teleportProfileExtension = new RequestTeleportProfileExtension(services);
        ProfileExtensions.register(teleportProfileExtension);

        // The commands were registered during bootstrap, long before any of this existed, and have been
        // answering "not started yet" until now. See TpaCommands.
        TpaCommands.ready(services);

        log.info("Teleport requests are up: {}s to answer, {}s to stand still, {} player(s) have "
                        + "decided something.",
                settings.current().requestStanding(), settings.current().warmup(),
                prefsFile.tracked());
    }

    /**
     * Opening the screens, which is the only thing in the module that knows the menu classes exist.
     *
     * <p>Each passes {@code null} as the parent when it is an entry point from a command, because Core
     * draws no Back button on a parentless menu — which is right, since there is nothing behind it.
     * The pages reached from the hub pass the hub, so Back goes there.
     */
    private final class LiveScreens implements ITpaScreensOpener {

        @Override
        public void hub(Player viewer) {
            new TpaHubMenu(services, viewer, null).open();
        }

        @Override
        public void whoToAsk(Player viewer, TpaKind kind) {
            new WhoToAskMenu(services, viewer, new TpaHubMenu(services, viewer, null), kind).open();
        }

        @Override
        public void requests(Player viewer) {
            new RequestsMenu(services, viewer, new TpaHubMenu(services, viewer, null)).open();
        }

        @Override
        public void blocked(Player viewer) {
            new BlockedMenu(services, viewer, new TpaHubMenu(services, viewer, null)).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return TpaCommands.declared();
    }

    @Override
    public void disable() {
        TpaCommands.stopped();
        if (teleportProfileExtension != null) {
            ProfileExtensions.unregister(teleportProfileExtension);
        }

        // Somebody mid-wait when the module stops must not be left standing still for a teleport that
        // will never come, and the countdown tasks must not outlive the plugin that scheduled them.
        if (travel != null) {
            travel.clear();
        }
        // Requests are in memory only and go with the module. Deliberately: a request from before a
        // restart is one whose asker has long since walked away, and answering it would teleport
        // somebody to a place that made sense an hour ago.
        if (requests != null) {
            requests.clear();
        }
        // The block list is not: it is written on every change, so there is nothing here to flush.

        // The listeners are unregistered by the context, in the reverse order they were registered —
        // see ModuleContext.closeWith.
    }

    /** Who has asked whom, for a host that wants to show it. */
    public TpaRequests requests() {
        return requests;
    }
}
