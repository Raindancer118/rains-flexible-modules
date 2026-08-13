package de.raindancer.modules.worldgate;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.worldgate.listener.WorldGatePortalListener;
import de.raindancer.modules.worldgate.model.Dimension;
import de.raindancer.modules.worldgate.rules.GateRule;
import de.raindancer.modules.worldgate.service.WorldGateService;
import de.raindancer.modules.worldgate.store.GateStateStore;
import de.raindancer.modules.worldgate.util.PermissionNodes;
import org.bukkit.Server;

import java.util.List;

/**
 * Nether and End access, as a module: lock it, drain it, close it, or pull everybody out.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsWorldGate}, a plugin of its own. Hosted
 * inside another plugin it is one feature among several, and the code below cannot tell which.
 *
 * <h2>What is deliberately not here</h2>
 * A world called {@code world_nether} is not special to Bukkit — cancelling a {@code PlayerPortalEvent}
 * is the whole mechanism, and {@code RainsCore}'s {@code FarmWorldPortalListener} already shows the
 * exact shape that takes. Nothing about locking a dimension needed a place in Core: the two dimensions,
 * their three states and the evacuation are wholly this module's own feature, not shared behaviour a
 * second plugin would also want.
 *
 * <h2>What is left, and what this module actually is</h2>
 * Three states per dimension, one rule deciding whether a crossing is allowed, one small store for the
 * state an admin has actually set, and the one command that reads and changes it.
 */
public final class WorldGateModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("worldgate", "World Gate", "1.0.0")
            .describedAs("Locks, drains or closes the Nether and the End to entry, and evacuates "
                    + "whoever is still inside")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<WorldGateSettings> settings;
    private WorldGateService gate;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(WorldGateSettings.class, WorldGateSettings.DEFAULTS);

        // The module's own wording, offered as a floor below anything the owner has written. Looked
        // up beside this class rather than at "/messages.yml": RainsCore ships one at its own jar
        // root and join-classpath puts Core's resources on this module's classpath, so a root lookup
        // is a race between two files with the same name.
        context.core().messages().defineFrom(
                WorldGateModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        // Before anything asks. An unregistered permission resolves to "operators only", which would
        // refuse /worldgate status to every ordinary player on the server.
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        GateStateStore store = new GateStateStore(context.dataFolder());
        gate = new WorldGateService(store, log, context.core().messages(), settings.current());
        gate.load();
        settings.onChange(gate::settings);

        GateRule rule = new GateRule();
        context.listener(new WorldGatePortalListener(gate, rule, context.core().messages()));

        WorldGateServices services = new WorldGateServices(context.plugin(), server, log,
                context.core().messages(), context.chat().brand(), settings::current, settings, gate);

        // The command was registered during bootstrap, long before any of this existed, and has been
        // answering "not started yet" until now. See WorldGateCommands.
        WorldGateCommands.ready(services);

        log.info("World Gate is up: the Nether is {}, the End is {}.",
                gate.state(Dimension.NETHER), gate.state(Dimension.END));
    }

    @Override
    public List<ModuleCommand> commands() {
        return WorldGateCommands.declared();
    }

    @Override
    public void disable() {
        WorldGateCommands.stopped();

        // The listener is unregistered by the context, in the reverse order it was registered — see
        // ModuleContext.closeWith. Nothing here holds anything that needs flushing beyond what set()
        // already wrote synchronously to disk on every change.
    }
}
