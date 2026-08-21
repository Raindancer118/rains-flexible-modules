package de.raindancer.modules.warp;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelListener;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.warp.listener.WarpSessionListener;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import de.raindancer.modules.warp.screen.AdminWarpMenu;
import de.raindancer.modules.warp.screen.WarpCategoryMenu;
import de.raindancer.modules.warp.screen.WarpConfigMenu;
import de.raindancer.modules.warp.screen.WarpEditMenu;
import de.raindancer.modules.warp.screen.WarpListMenu;
import de.raindancer.modules.warp.service.TravelService;
import de.raindancer.modules.warp.service.WarpAdminService;
import de.raindancer.modules.warp.store.WarpCatalogue;
import de.raindancer.modules.warp.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/**
 * Warps, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsWarps}, a plugin of its own. Hosted
 * inside another plugin it is one feature among several, and the code below cannot tell which.
 *
 * <h2>What is Core's, and what is this module's</h2>
 * The places are Core's — a warp is a POI of kind {@code warp}, stored on
 * {@code context.core().places()}, so persistence, atomic writes, worlds that are not loaded and
 * "is this reachable" are solved and tested there, and a ghast line can fly somebody to a warp
 * because of it. The warm-up, the movement cancelling, finding somewhere safe to land and the
 * teleport itself are Core's {@code Travel}, which is the same code the teleport requests and the
 * homes use. The menu framework, the wording plumbing and the chat prompt machinery are Core's too.
 *
 * <p>What a warp actually <em>is</em> — the model, the registry that turns places into warps, the
 * cooldown between one player's goes, who may use which warp, how a server groups them, and the four
 * screens — is this module's own, in {@link de.raindancer.modules.warp.model} and
 * {@link de.raindancer.modules.warp.store}. It used to live in Core, behind {@code core.warps()};
 * moved out because "a warp" is a product concept this module owns, not a mechanism every plugin on
 * the server needs — Core only ever had one consumer of it.
 *
 * <h2>Who may use which warp</h2>
 * Three answers rather than two — everybody, the staff, or whoever holds one particular permission —
 * because "staff" and "the people who may reach the build world" are different groups on every server
 * that has both. Stored as nothing but the one permission Core already keeps on a place, so there is
 * no second field to disagree with the first about whether the staff room is open.
 */
public final class WarpModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("warps", "Warps", "1.2.2")
            .describedAs("Named places anybody can be sent to, with a menu to pick one from — and "
                    + "warps only the staff, or one permission, can reach")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<WarpSettings> settings;

    private de.raindancer.modules.warp.store.WarpRegistry registry;
    private WarpCatalogue catalogue;
    private Travel travel;
    private TravelService travelling;
    private WarpAdminService admin;

    private WarpServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(WarpSettings.class, WarpSettings.DEFAULTS);

        // The module's own wording, offered as a floor below anything the owner has written. Not
        // Messages.load: there is one Messages on the server and it is Core's, so loading would
        // throw away Core's own lines and every other module's with them.
        //
        // Looked up beside this class rather than at "/messages.yml": RainsCore ships one at the
        // root of its own jar and join-classpath puts it on this module's classpath, so a root
        // lookup is a race between two files with the same name.
        //
        // Signed with this module's own brand, so its sentences say Warps. Without the signature the
        // section is unowned, and an unowned section wears whichever module plugin started last — on
        // the live server "<name> is set, here." arrived branded "Moderation »".
        context.core().messages().defineFrom(
                WarpModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        // Before anything asks. An unregistered permission resolves to "operators only", which would
        // refuse the warp menu to every ordinary player on the server.
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        WarpAccessRule access = new WarpAccessRule();
        // This module's own registry, on Core's shared places — not a second store, and not Core's
        // any more either. See WarpRegistry's own class note for why it moved out from behind
        // core.warps().
        registry = new de.raindancer.modules.warp.store.WarpRegistry(context.core().places(),
                System::currentTimeMillis);
        catalogue = new WarpCatalogue(registry, context.core().places()::flush);
        travel = new Travel(context.plugin(), context.core().safety(), context.core().audit());
        travelling = new TravelService(catalogue, registry, travel, access,
                context.core().messages(), settings.current());
        admin = new WarpAdminService(catalogue, access, context.core().messages(),
                settings.current());

        // The cooldown lives on this module's WarpRegistry, so it has to be pushed in at start as
        // well as on reload — otherwise the file says thirty seconds and nothing is enforced until
        // somebody edits it.
        registry.cooldown(Duration.ofSeconds(settings.current().cooldown()));

        services = new WarpServices(context.plugin(), server, context.core(), log,
                context.core().messages(), context.chat(), context.chat().brand(),
                settings::current, settings,
                catalogue, access, travel, travelling, admin,
                new LiveScreens());

        // Every setting is a snapshot, so a reload hands each service a fresh one. Missing one of
        // these is a subsystem that keeps yesterday's numbers until the next restart, which is the
        // sort of defect reported as "the config does not work".
        settings.onChange(fresh -> {
            travelling.settings(fresh);
            admin.settings(fresh);
            // Pushed here too, not only at startup — otherwise a changed cooldown reads correctly in
            // every screen and command but goes on enforcing whatever the file said when the module
            // started, until the next restart.
            registry.cooldown(Duration.ofSeconds(fresh.cooldown()));
        });

        // Core's, not a fourth copy of "stand still or it is cancelled". Whether being hurt counts
        // is the owner's decision, so it is read from the settings at registration — a server with
        // mobs at spawn and a five-second warm-up otherwise has a warp nobody can complete.
        context.listener(new TravelListener(travel, settings.current().hurtCancelsWarmup()));
        context.listener(new WarpSessionListener(services));

        // The command was registered during bootstrap, long before any of this existed, and has been
        // answering "not started yet" until now. See WarpCommands.
        WarpCommands.ready(services);

        log.info("Warps is up: {} warp(s), {}s to stand still, {}s between goes.",
                catalogue.count(), settings.current().warmup(), settings.current().cooldown());
    }

    /**
     * Opening the screens, which is the only thing in the module that knows the menu classes exist.
     *
     * <p>An inner class rather than five lambdas at the construction site: a new screen is one method
     * here rather than one more argument there.
     *
     * <p>Every one of these passes {@code null} as the parent, because each is an entry point from a
     * command and Core draws no Back button on a parentless menu — which is right, since there is
     * nothing behind it to go back to. A screen opening another screen passes {@code this} instead.
     */
    private final class LiveScreens implements IWarpScreensOpener {

        @Override
        public void warps(Player viewer) {
            new WarpListMenu(services, viewer, null).open();
        }

        @Override
        public void category(Player viewer, String category) {
            WarpListMenu.inCategory(services, viewer, null, category).open();
        }

        @Override
        public void categories(Player viewer) {
            new WarpCategoryMenu(services, viewer, null).open();
        }

        @Override
        public void admin(Player viewer) {
            new AdminWarpMenu(services, viewer, null).open();
        }

        @Override
        public void edit(Player viewer, String warpName) {
            new WarpEditMenu(services, viewer, null, warpName).open();
        }

        @Override
        public void config(Player viewer) {
            new WarpConfigMenu(services, viewer, null).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return WarpCommands.declared();
    }

    @Override
    public void disable() {
        WarpCommands.stopped();

        // Somebody mid-warm-up when the module stops must not be left standing still for a teleport
        // that will never come, and the countdown tasks must not outlive the plugin that scheduled
        // them. Nothing else has to be written: the warps are places, and Core owns those.
        if (travel != null) {
            travel.clear();
        }

        // The listeners are unregistered by the context, in the reverse order they were registered —
        // see ModuleContext.closeWith.
    }

    /** The warps on this server, for a host that wants to show them. */
    public WarpCatalogue warps() {
        return catalogue;
    }
}
