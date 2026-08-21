package de.raindancer.modules.farmworld;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelListener;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.farmworld.listener.FarmSessionListener;
import de.raindancer.modules.farmworld.listener.FarmWorldPortalListener;
import de.raindancer.modules.farmworld.model.WorldSet;
import de.raindancer.modules.farmworld.rules.FarmAccessRule;
import de.raindancer.modules.farmworld.store.FarmWorldState;
import de.raindancer.modules.farmworld.store.FarmWorlds;
import de.raindancer.modules.farmworld.screen.FarmWorldConfigMenu;
import de.raindancer.modules.farmworld.screen.FarmWorldListMenu;
import de.raindancer.modules.farmworld.screen.FarmWorldManageMenu;
import de.raindancer.modules.farmworld.screen.FarmWorldMenu;
import de.raindancer.modules.farmworld.service.FarmAdminService;
import de.raindancer.modules.farmworld.service.FarmTravelService;
import de.raindancer.modules.farmworld.service.NoticeService;
import de.raindancer.modules.farmworld.store.FarmWorldCatalogue;
import de.raindancer.modules.farmworld.util.PermissionNodes;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Random;

/**
 * Farm worlds, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsFarmWorlds}, a plugin of its own. Hosted inside
 * another plugin it is one feature among several, and the code below cannot tell which.
 *
 * <h2>What is Core's, and what is this module's</h2>
 * The warm-up, the movement cancelling, finding somewhere safe to land and the teleport are Core's
 * {@code Travel}, the same code the warps, the homes and the teleport requests use. The wait between
 * trips is Core's {@code Cooldowns}. The menu, the buttons, the wording, the settings and the
 * confirmation dialog are Core's too.
 *
 * <p>What a farm world actually <em>is</em> — three linked worlds with its own nether and end, the
 * portal linking that keeps a farm portal inside the farm world, the schedule, the recorded times,
 * and {@code FarmWorldState.mayDelete} — is this module's own, in
 * {@link de.raindancer.modules.farmworld.model} and {@link de.raindancer.modules.farmworld.store}.
 * It used to live in Core, behind {@code core.farmWorlds()}; moved out because a farm world is a
 * product concept this module owns, not a mechanism every plugin on the server needs — Core only
 * ever had one consumer of it, built on Core's still-generic world regeneration and chunk-holding
 * primitives instead.
 *
 * <p>What is left beyond owning the concept itself: <b>how somebody gets in, and what they are told
 * before the ground under them is regenerated.</b>
 *
 * <h2>The two things that make a farm world work rather than merely exist</h2>
 * <ul>
 *   <li><b>Arrivals are scattered.</b> A plain {@code /farmworld} puts everybody at the world's spawn. Do
 *       that and the first hundred blocks are bare within a day, and from then on every arrival is a five-minute
 *       walk before they can start — so the farm world is a corridor, and the only fix left is to regenerate it
 *       more often, which throws away everybody's work to solve a problem that was never about the far parts of
 *       the map.</li>
 *   <li><b>The server is warned first.</b> The regen timer below tells whoever is standing there as it
 *       happens, before the world it decided was due goes. Somebody two hours into a trip, with a base
 *       and a full set of chests, finds out at the moment all of it stops existing. Nothing was lost
 *       that a farm world did not promise to lose — but a promise nobody was reminded of is one people
 *       report as a bug.</li>
 * </ul>
 *
 * <p>Only one timer decides a farm world is due, now that this module owns the whole concept — the
 * two-timer race this class note used to warn about (a warning timer here, a regen timer in Core)
 * cannot happen any more because there is only one of either kind left.
 */
public final class FarmWorldModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("farmworlds", "Farm Worlds", "1.1.3")
            .describedAs("Somewhere to strip-mine that is regenerated — arrived at "
                    + "somewhere different every time, and announced before it goes")
            .by("Raindancer118");

    /**
     * How often the warnings are looked at: once a second.
     *
     * <p>The work is a read of what is already in memory and a question to a rule, plus — only for a farm world
     * inside its last five minutes — asking the server who is standing in it. Nothing touches the disk.
     *
     * <p>Once a second rather than once in twenty, for two reasons that both appeared as soon as there was a
     * countdown bar: a bar that moves in twenty-second steps reads as a broken bar, and the last notice before
     * three worlds are deleted arriving up to twenty seconds late is the difference between useful and cruel.
     */
    private static final long EVERY_SECOND = 20L;

    /** How often a farm world's schedule is checked. Its own, much slower timer: regenerating stops
     * the server for as long as the disk takes, so it is checked once a minute rather than folded
     * in with the warning ticker above. */
    private static final long REGEN_CHECK_TICKS = 20L * 60L;

    /** How often the recorded times reach disk, absent a change that flushed them sooner — a
     * crash between the two must not lose more than this. Kept in step with what RainsCore used
     * to save this on, back when it was Core's. */
    private static final long SAVE_PERIOD_SECONDS = 120L;

    private LogChannel log;
    private SettingsStore<FarmWorldSettings> settings;

    private FarmWorldState state;
    private FarmWorlds farms;
    private FarmWorldCatalogue catalogue;
    private Travel travel;
    private FarmTravelService travelling;
    private FarmAdminService admin;
    private NoticeService notices;
    private ScheduledTask watching;
    private ScheduledTask regenChecking;
    private ScheduledTask saving;

    private FarmWorldServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(FarmWorldSettings.class, FarmWorldSettings.DEFAULTS);

        // The module's own wording, offered as a floor below anything the owner has written. Not
        // Messages.load: there is one Messages on the server and it is Core's, so loading would throw away
        // Core's own lines and every other module's with them.
        //
        // Looked up beside this class rather than at "/messages.yml": RainsCore ships one at the root of its
        // own jar and join-classpath puts it on this module's classpath, so a root lookup is a race between
        // two files with the same name.
        //
        // Signed with this module's own brand, so its sentences say Farm Worlds. Without the signature the
        // section is unowned and wears whichever module plugin started last — on the live server one module's
        // "<name> is set, here." arrived branded "Moderation »".
        context.core().messages().defineFrom(
                FarmWorldModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        // This module's own database now, not Core's shared one — see FarmWorldState.SCHEMA. A server
        // that ran an older version of this module kept these same rows in Core's core.db; migrateFrom
        // copies them across once, and is safe to call on every boot afterwards.
        de.raindancer.core.data.sql.Database ownDatabase =
                context.core().databases().of("farmworld", FarmWorldState.SCHEMA);
        state = new FarmWorldState(context.dataFolder().resolve("farmworlds.yml"), ownDatabase);
        state.migrateFrom(context.core().databases().core());
        state.load();
        farms = new FarmWorlds(context.plugin(), state);
        for (WorldSet set : state.all()) {
            farms.ensure(set);
        }
        catalogue = new FarmWorldCatalogue(farms);

        // Before anything asks, and with one node per farm world that already exists — an unregistered
        // permission resolves to "operators only", which would refuse every farm world to every ordinary
        // player on the server.
        int registered = PermissionNodes.register(server, catalogue.names());
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        FarmAccessRule access = new FarmAccessRule();
        travel = new Travel(context.plugin(), context.core().safety(), context.core().audit());
        travelling = new FarmTravelService(catalogue, travel, access, context.core().messages(),
                context.core().effects(), settings.current(), new Random());
        admin = new FarmAdminService(context.plugin(), server, catalogue, access,
                context.core().messages(), log, settings.current());
        // Everybody, as one audience. The server itself: a warning about a farm world being regenerated is
        // server news rather than a message to the people standing in it — somebody planning a trip needs it
        // more than somebody who is already there and can see the sky.
        // The bar and the ticking are for the people standing in the farm world; the chat notice is for the
        // server. Two audiences with two different problems — see NoticeService.
        notices = new NoticeService(catalogue, context.core().messages(), server,
                context.core().bossBars(), context.core().effects(), settings.current());

        services = new FarmWorldServices(context.plugin(), server, context.core(), log,
                context.core().messages(), context.chat(), context.chat().brand(),
                settings::current, settings,
                catalogue, access, travel, travelling, admin, notices,
                new LiveScreens());

        // Every setting is a snapshot, so a reload hands each service a fresh one. Missing one of these is a
        // subsystem that keeps yesterday's numbers until the next restart, which is the sort of defect
        // reported as "the config does not work".
        settings.onChange(fresh -> {
            travelling.settings(fresh);
            admin.settings(fresh);
            notices.settings(fresh);
        });

        // Core's, not a fourth copy of "stand still or it is cancelled". Whether being hurt counts is the
        // owner's decision, so it is read from the settings at registration — a server with mobs at spawn and
        // a five-second warm-up otherwise has a trip nobody can complete.
        context.listener(new TravelListener(travel, settings.current().hurtCancelsWarmup()));
        context.listener(new FarmSessionListener(services));
        context.listener(new FarmWorldPortalListener(services));

        // The warnings, the bar and the ticking. On the global region scheduler rather than an async timer: it
        // reads world player lists and sends messages, and on Folia the only thread that may do either for every
        // player on the server is that one.
        watching = Scheduling.globalTimer(context.plugin(), EVERY_SECOND, EVERY_SECOND,
                task -> notices.check());
        if (watching == null) {
            // The plugin is being disabled, most likely. Said rather than left: a farm world that regenerates
            // with no warning at all is the one complaint this module exists to answer.
            log.error("The warning timer could not be started, so nothing will be announced before a "
                    + "farm world is regenerated.");
        } else {
            context.closeWith(watching::cancel);
        }

        // The only timer left that decides a farm world is due — see the class note on why there is
        // now exactly one, where there used to be this one plus Core's own.
        regenChecking = Scheduling.globalTimer(context.plugin(), REGEN_CHECK_TICKS, REGEN_CHECK_TICKS,
                task -> farms.regenerateWhatIsDue());
        if (regenChecking != null) {
            context.closeWith(regenChecking::cancel);
        }

        // Off the region thread: this writes a file and a database, and the global timer above runs
        // on the thread that ticks the world. A crash between two of these loses at most this long —
        // a clean shutdown flushes immediately regardless, see disable().
        saving = Scheduling.asyncTimer(context.plugin(), SAVE_PERIOD_SECONDS, SAVE_PERIOD_SECONDS,
                task -> {
                    if (catalogue.isDirty()) {
                        catalogue.flush();
                    }
                });
        if (saving != null) {
            context.closeWith(saving::cancel);
        }

        // The command was registered during bootstrap, long before any of this existed, and has been answering
        // "not started yet" until now. See FarmWorldCommands.
        FarmWorldCommands.ready(services);

        int farms = catalogue.count();
        if (farms == 0) {
            log.info("Farm Worlds is up. There are none yet — /farm create <name> [how often] makes one.");
        } else {
            log.info("Farm Worlds is up: {} farm world(s), {}s to stand still, {} between trips.",
                    farms, settings.current().warmup(),
                    Times.describe(settings.current().cooldownFor()));
        }
    }

    /**
     * Opening the screens, which is the only thing in the module that knows the menu classes exist.
     *
     * <p>An inner class rather than four lambdas at the construction site: a new screen is one method here
     * rather than one more argument there.
     *
     * <p>Every one of these passes {@code null} as the parent, because each is an entry point from a command and
     * Core draws no Back button on a parentless menu — which is right, since there is nothing behind it to go
     * back to. A screen opening another screen passes {@code this} instead.
     */
    private final class LiveScreens implements IFarmWorldScreensOpener {

        @Override
        public void farms(Player viewer) {
            new FarmWorldListMenu(services, viewer, null).open();
        }

        @Override
        public void farm(Player viewer, String name) {
            new FarmWorldMenu(services, viewer, null, name).open();
        }

        @Override
        public void manage(Player viewer, String name) {
            new FarmWorldManageMenu(services, viewer, null, name).open();
        }

        @Override
        public void config(Player viewer) {
            new FarmWorldConfigMenu(services, viewer, null).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return FarmWorldCommands.declared();
    }

    @Override
    public void disable() {
        FarmWorldCommands.stopped();

        // Somebody mid-warm-up when the module stops must not be left standing still for a trip that will
        // never come, and the countdown tasks must not outlive the plugin that scheduled them.
        if (travel != null) {
            travel.clear();
        }

        // What the module changed about a farm world's definition, if anything has not reached the disk yet.
        // Written here rather than trusted to the last command's own write: a schedule that is live now and
        // gone after a restart deletes three worlds on a day nobody expected.
        if (catalogue != null && catalogue.isDirty()) {
            catalogue.flush();
        }

        // The timer is cancelled by the context, which was told to close it — see ModuleContext.closeWith. The
        // listeners are unregistered there too, in the reverse order they were registered.
    }

    /** The farm worlds on this server, for a host that wants to show them. */
    public FarmWorldCatalogue farmWorlds() {
        return catalogue;
    }
}
