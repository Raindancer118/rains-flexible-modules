package de.raindancer.modules.rtp;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelListener;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.rtp.listener.RtpSessionListener;
import de.raindancer.modules.rtp.rules.RtpRule;
import de.raindancer.modules.rtp.screen.RtpChooserMenu;
import de.raindancer.modules.rtp.service.RtpService;
import de.raindancer.modules.rtp.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Random;

/**
 * Random teleport, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsRandomTeleport}, a plugin of its own.
 * Hosted inside another plugin it is one feature among several, and the code below cannot tell which.
 *
 * <h2>What is deliberately not here</h2>
 * Nearly all of it. The warm-up, the movement cancelling, finding somewhere safe to land and the
 * teleport itself are Core's {@code Travel} — the same code the warps, the homes and the teleport
 * requests use. The ring somebody lands in is Core's {@code Scatter}. The cooldown is Core's
 * {@code Cooldowns}, behind {@link RtpService}. The settings screen and the wording come from Core's
 * settings system and message tables.
 *
 * <h2>What is left, and what this module actually is</h2>
 * The one command, the one rule (is this world even allowed to be jumped around in), and the ordering
 * that turns "a player typed {@code /rtp}" into a call to {@code Travel}.
 *
 * <h2>Why there is no screen, model or store package</h2>
 * Nothing here is a thing with an identity to browse, edit or persist — there is no list to page
 * through and no per-item state to keep across a restart. Everything a random teleport needs is either
 * a setting, reached through {@code /settings} like every other module's, or arithmetic Core already
 * owns.
 */
public final class RtpModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("rtp", "Random Teleport", "1.0.0")
            .describedAs("Sends a player somewhere random in their own world, inside a ring an owner "
                    + "sets — the warm-up, the safe landing and the teleport are RainsCore's Travel")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<RtpSettings> settings;

    private Travel travel;
    private RtpService rtp;
    private RtpServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(RtpSettings.class, RtpSettings.DEFAULTS);

        // The module's own wording, offered as a floor below anything the owner has written. Not
        // Messages.load: there is one Messages on the server and it is Core's, so loading would throw
        // away Core's own lines and every other module's with them.
        //
        // Looked up beside this class rather than at "/messages.yml": RainsCore ships one at its own
        // jar root and join-classpath puts Core's resources on this module's classpath, so a root
        // lookup is a race between two files with the same name.
        context.core().messages().defineFrom(
                RtpModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        // Before anything asks. An unregistered permission resolves to "operators only", which would
        // refuse /rtp to every ordinary player on the server.
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        RtpRule rule = new RtpRule();
        travel = new Travel(context.plugin(), context.core().safety());
        rtp = new RtpService(context.plugin(), travel, context.core().safety(), rule,
                context.core().messages(), context.core().effects(), context.core().actionBars(),
                log, settings.current(), new Random());

        // Every setting is a snapshot, so a reload hands the service a fresh one. Missing this is a
        // service that keeps yesterday's cooldown until the next restart, which gets reported as "the
        // config does not work".
        settings.onChange(rtp::settings);

        // Core's, not a second copy of "stand still or it is cancelled". Whether being hurt counts is
        // the owner's decision, read from the settings at registration — a server with mobs at spawn
        // and a five-second warm-up otherwise has a random teleport nobody can complete.
        context.listener(new TravelListener(travel, settings.current().hurtCancelsWarmup()));
        context.listener(new RtpSessionListener(rtp));

        services = new RtpServices(context.plugin(), server, log,
                context.core().messages(), context.chat().brand(), settings::current, settings, rtp,
                new LiveScreens());

        // The command was registered during bootstrap, long before any of this existed, and has been
        // answering "not started yet" until now. See RtpCommands.
        RtpCommands.ready(services);

        log.info("Random Teleport is up: {}-{} block ring, {}s to stand still, {}s between goes.",
                settings.current().minRadius(), settings.current().maxRadius(),
                settings.current().warmup(), settings.current().cooldown());
    }

    /**
     * Opening the one screen this module has, which is the only thing here that knows the menu class
     * exists.
     *
     * <p>Refers to the {@link #services} field rather than capturing a local, because this is built
     * as part of constructing that very record — by the time a player actually opens the menu,
     * {@code enable} has long since finished and the field holds the real thing.
     */
    private final class LiveScreens implements IRtpScreensOpener {

        @Override
        public void chooser(Player viewer) {
            // null parent: this is an entry point from a command, and Core draws no Back button on a
            // parentless menu — right, since there is nowhere behind it to go back to.
            new RtpChooserMenu(services, viewer, null).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return RtpCommands.declared();
    }

    @Override
    public void disable() {
        RtpCommands.stopped();

        // Somebody mid-warm-up when the module stops must not be left standing still for a teleport
        // that will never come, and the countdown tasks must not outlive the plugin that scheduled
        // them.
        if (travel != null) {
            travel.clear();
        }

        // The listeners are unregistered by the context, in the reverse order they were registered —
        // see ModuleContext.closeWith.
    }
}
