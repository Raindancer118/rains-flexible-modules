package de.raindancer.modules.chained;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.chained.listener.ChainMovementListener;
import de.raindancer.modules.chained.rules.ChainDistanceRule;
import de.raindancer.modules.chained.screen.ChainAdminMenu;
import de.raindancer.modules.chained.screen.ChainStatusMenu;
import de.raindancer.modules.chained.service.ChainService;
import de.raindancer.modules.chained.store.ChainPairStore;
import de.raindancer.modules.chained.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * "RainsChained", as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsChained}, a plugin of its own. Hosted
 * inside another plugin it is one feature among several, and the code below cannot tell which.
 *
 * <h2>What is deliberately not here</h2>
 * The speedrun timer, its pause-while-everybody-is-offline behaviour, the advancement and death end
 * conditions, and throwing a world away and making it again are all RainsCore's — a brand-new
 * {@code core.world.speedrun} package built for exactly this kind of module, so a "who is still
 * around" question, an accidental double-finish and a half-deleted world folder are solved and
 * tested once rather than once per challenge plugin. The menu, the buttons, the wording, the
 * settings, the cooldown and the shared boss bar are Core's too.
 *
 * <p>What is left, and what this module actually is: {@link de.raindancer.modules.chained.model.ChainPair
 * two players registered as chained}, the invisible wall that keeps them from separating too far,
 * and the two screens.
 */
public final class ChainedModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("chained", "Chained", "1.0.0")
            .describedAs("Two players, mechanically chained together — separating too far is "
                    + "simply blocked, and a speedrun timer runs underneath the run")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<ChainedSettings> settings;

    private ChainPairStore pairs;
    private ChainDistanceRule distance;
    private ChainService chain;
    private ChainMovementListener movement;

    private ChainedServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(ChainedSettings.class, ChainedSettings.DEFAULTS);

        // The module's own wording, offered as a floor below anything the owner has written. Not
        // Messages.load: there is one Messages on the server and it is Core's, so loading would
        // throw away Core's own lines and every other module's with them.
        //
        // Looked up beside this class rather than at "/messages.yml": RainsCore ships one at the
        // root of its own jar and join-classpath puts it on this module's classpath, so a root
        // lookup is a race between two files with the same name.
        //
        // Signed with this module's own brand, so its sentences say Chained. Without the signature
        // the section is unowned, and an unowned section wears whichever module plugin started last.
        context.core().messages().defineFrom(
                ChainedModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        // Before anything asks. An unregistered permission resolves to "operators only".
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        pairs = new ChainPairStore();
        distance = new ChainDistanceRule();
        chain = new ChainService(context.plugin(), pairs, context.core().bossBars(),
                context.core().messages(), settings.current());

        services = new ChainedServices(context.plugin(), server, context.core(), log,
                context.core().messages(), context.chat(), context.chat().brand(),
                settings::current, settings,
                pairs, distance, chain,
                new LiveScreens());

        // Every setting is a snapshot, so a reload hands each service a fresh one. Missing one of
        // these is a subsystem that keeps yesterday's numbers until the next restart.
        settings.onChange(fresh -> {
            chain.settings(fresh);
            if (movement != null) {
                movement.refreshCooldown();
            }
        });

        movement = new ChainMovementListener(services);
        context.listener(movement);

        // The command was registered during bootstrap, long before any of this existed, and has been
        // answering "not started yet" until now. See ChainedCommands.
        ChainedCommands.ready(services);

        log.info("Chained is up: {} pair(s), {} blocks apart at most.",
                pairs.count(), settings.current().maxDistance());
    }

    /**
     * Opening the screens, which is the only thing in the module that knows the menu classes exist.
     *
     * <p>Both pass {@code null} as the parent: each is an entry point from a command, and Core draws
     * no Back button on a parentless menu — right, since there is nothing behind it to go back to.
     */
    private final class LiveScreens implements IChainedScreensOpener {

        @Override
        public void status(Player viewer) {
            new ChainStatusMenu(services, viewer, null).open();
        }

        @Override
        public void admin(Player viewer) {
            new ChainAdminMenu(services, viewer, null).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return ChainedCommands.declared();
    }

    @Override
    public void disable() {
        ChainedCommands.stopped();

        // Nobody is left mid-run for a clock that will never resume, and the shared boss bars that
        // were showing it are taken away rather than left stuck on whatever they last said.
        if (chain != null) {
            chain.shutdown();
        }

        // The listener is unregistered by the context, in the reverse order it was registered — see
        // ModuleContext.closeWith.
    }

    /** The pairs on this server, for a host that wants to show them. */
    public ChainPairStore pairs() {
        return pairs;
    }
}
