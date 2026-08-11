package de.raindancer.modules.homes;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelListener;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.homes.listener.HomeSessionListener;
import de.raindancer.modules.homes.model.Home;
import de.raindancer.modules.homes.rules.HomeLimitRule;
import de.raindancer.modules.homes.rules.HomeNameRule;
import de.raindancer.modules.homes.screen.HomeEditMenu;
import de.raindancer.modules.homes.screen.HomeIconMenu;
import de.raindancer.modules.homes.screen.HomeListMenu;
import de.raindancer.modules.homes.service.HomeKeepingService;
import de.raindancer.modules.homes.service.HomeTravelService;
import de.raindancer.modules.homes.store.HomeCatalogue;
import de.raindancer.modules.homes.store.LegacyHomesFile;
import de.raindancer.modules.homes.store.SetHomeConfigFile;
import de.raindancer.modules.homes.store.SetHomePluginFile;
import de.raindancer.modules.homes.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Homes, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsHomes}, a plugin of its own. Hosted inside
 * another plugin it is one feature among several, and the code below cannot tell which.
 *
 * <h2>What is deliberately not here any more</h2>
 * Most of what the standalone plugin was. The places are RainsCore's — a home is a POI of kind
 * {@code home}, so persistence, the write-to-a-temporary-then-move dance, worlds that are not loaded and
 * "is this reachable" are solved and tested there. The standing still, the cancelling on movement and
 * damage, finding somewhere safe to land and the teleport itself are Core's {@code Travel} — which is
 * the code this plugin's own {@code HomeService} became, after it turned out to be identical to the
 * teleport requests' copy down to the helper that decides whether somebody has moved. The wait between
 * teleports is Core's {@code Cooldowns}. The menu, the buttons, the wording, the settings, the chat
 * prompt and the confirmation dialog are Core's too.
 *
 * <p>What is left, and what this module actually is: what a home means. How many somebody may have, what
 * it may be called, what block it shows as, and three screens.
 *
 * <h2>The migration</h2>
 * An upgrading server's homes are in a {@code homes.yml}, in one of two shapes: this module's own
 * predecessor's, read by {@link HomeCatalogue#importLegacy}, or the third-party {@code SetHome}
 * plugin's, read by {@link HomeCatalogue#importSetHomePlugin}. Either reads its file once into the place
 * store and renames it aside — kept, not deleted, so a server that has to roll back still has every home.
 */
public final class HomeModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("homes", "Homes", "2.2.0")
            .describedAs("Somewhere of your own to come back to: name it, set it, go to it, and pick "
                    + "from a menu of them")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<HomeSettings> settings;

    private HomeCatalogue homes;
    private Travel travel;
    private HomeTravelService travelling;
    private HomeKeepingService keeping;

    private HomeServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();

        // A sibling of every plugin's data folder, this module's included — not of
        // context.dataFolder(), which is only that when the module is standalone and a corner of the
        // host's otherwise. Going through the host plugin's own data folder is the one way to find
        // "plugins/" that holds whether this module is standalone or a guest, and both migrations
        // below need it.
        Path pluginsFolder = context.plugin().getDataFolder().toPath().getParent();

        // Before context.settings() creates this module's own config.yml with the shipped defaults —
        // once that file exists, "an owner has settings of their own" and "SetHome's are still there
        // to bring across" can no longer be told apart. RainsHomes' own file wins whenever there is
        // one, the same rule the homes themselves follow and for the same reason: whatever an owner
        // has already been running with is the one they would call correct.
        HomeSettings defaults = HomeSettings.DEFAULTS;
        if (!Files.exists(context.dataFolder().resolve("config.yml"))) {
            Optional<Path> setHomeConfig = SetHomeConfigFile.locate(pluginsFolder, context.dataFolder());
            if (setHomeConfig.isPresent()) {
                Optional<SetHomeConfigFile.Values> values = SetHomeConfigFile.read(setHomeConfig.get());
                if (values.isPresent()) {
                    SetHomeConfigFile.Values imported = values.get();
                    defaults = HomeSettings.DEFAULTS
                            .withMax(imported.maxHomes())
                            .withCooldownSeconds(imported.cooldownSeconds())
                            .withCancelOnMove(imported.cancelOnMove());
                    log.info("Starting with SetHome's own settings: {} home(s) by default, "
                                    + "{}s between going home, moving {} the wait.",
                            imported.maxHomes(), imported.cooldownSeconds(),
                            imported.cancelOnMove() ? "cancels" : "does not cancel");
                }
                SetHomeConfigFile.setAside(setHomeConfig.get(), log);
            }
        }
        settings = context.settings(HomeSettings.class, defaults);

        // The module's own wording, offered as a floor below anything the owner has written. Not
        // Messages.load: there is one Messages on the server and it is Core's, so loading would throw
        // away Core's own lines and every other module's with them.
        //
        // Looked up beside this class rather than at "/messages.yml": RainsCore ships one at the root of
        // its own jar and join-classpath puts it on this module's classpath, so a root lookup is a race
        // between two files with the same name.
        //
        // Signed with this module's own brand, so its sentences say Homes. An unsigned section wears
        // whichever module plugin started last, which is how a home being set said "Moderation »".
        context.core().messages().defineFrom(
                HomeModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        // Before anything asks. An unregistered permission resolves to "operators only", which would
        // refuse homes to every ordinary player on the server.
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        HomeNameRule names = new HomeNameRule();
        HomeLimitRule limits = new HomeLimitRule();
        homes = new HomeCatalogue(context.core().places(), context.core().places()::flush);

        // Before anything reads the homes, so a server upgrading from the standalone plugin has them
        // all by the time the first player types /home.
        homes.importLegacy(context.dataFolder().resolve(LegacyHomesFile.FILE_NAME), log);

        // A second, independent migration: a server that ran the third-party SetHome plugin before
        // this one. SetHomePluginFile.locate does the actual finding, and does not insist on the exact
        // expected path: a renamed or differently-cased SetHome folder, or an export dropped by hand
        // next to this module's own files, is still found rather than silently leaving every home
        // behind.
        SetHomePluginFile.locate(pluginsFolder, context.dataFolder())
                .ifPresent(setHomeFile -> homes.importSetHomePlugin(setHomeFile, log));

        travel = new Travel(context.plugin(), context.core().safety());
        travelling = new HomeTravelService(travel, context.core().messages(), settings.current());
        keeping = new HomeKeepingService(homes, limits, names, context.core().messages(),
                settings.current());

        services = new HomeServices(context.plugin(), server, context.core(), log,
                context.core().messages(), context.chat(), context.chat().brand(),
                settings::current,
                homes, names, limits, travel, travelling, keeping,
                new LiveScreens());

        // Every setting is a snapshot, so a reload hands each service a fresh one. Missing one of these
        // is a subsystem that keeps yesterday's numbers until the next restart, which is the sort of
        // defect reported as "the config does not work".
        settings.onChange(fresh -> {
            travelling.settings(fresh);
            keeping.settings(fresh);
        });

        // Core's, not a fourth copy of "stand still or it is cancelled". Whether being hurt counts is
        // the owner's decision, so it is read at registration.
        context.listener(new TravelListener(travel, settings.current().cancelOnDamage()));
        context.listener(new HomeSessionListener(services));

        // The commands were registered during bootstrap, long before any of this existed, and have been
        // answering "not started yet" until now. See HomeCommands.
        HomeCommands.ready(services);

        log.info("Homes is up: {} home(s) kept, {} each by default, {}s to stand still.",
                context.core().places().ofKind(HomeCatalogue.KIND).size(),
                settings.current().homeLimit(), settings.current().warmup());
    }

    /**
     * Opening the screens, which is the only thing in the module that knows the menu classes exist.
     *
     * <p>Each passes {@code null} as the parent, because each is an entry point from a command and Core
     * draws no Back button on a parentless menu — which is right, since there is nothing behind it. A
     * screen opening another screen passes {@code this} instead.
     */
    private final class LiveScreens implements IHomeScreensOpener {

        @Override
        public void homes(Player viewer) {
            new HomeListMenu(services, viewer, null).open();
        }

        @Override
        public void edit(Player viewer, Home home) {
            new HomeEditMenu(services, viewer, null, home.name()).open();
        }

        @Override
        public void icon(Player viewer, Home home) {
            new HomeIconMenu(services, viewer, null, home.name()).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return HomeCommands.declared();
    }

    @Override
    public void disable() {
        HomeCommands.stopped();

        // Somebody mid-wait when the module stops must not be left standing still for a teleport that
        // will never come, and the countdown tasks must not outlive the plugin that scheduled them.
        // Nothing else has to be written: the homes are places, and Core owns those.
        if (travel != null) {
            travel.clear();
        }

        // The listeners are unregistered by the context, in the reverse order they were registered —
        // see ModuleContext.closeWith.
    }

    /** The homes on this server, for a host that wants to show them. */
    public HomeCatalogue homes() {
        return homes;
    }
}
