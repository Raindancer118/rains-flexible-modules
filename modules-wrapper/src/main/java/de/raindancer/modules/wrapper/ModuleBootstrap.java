package de.raindancer.modules.wrapper;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.Modules;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

/**
 * Registering the modules' commands, at the only moment Paper allows it.
 *
 * <h2>Why this class exists</h2>
 * Paper fires the {@code COMMANDS} lifecycle event during the <em>bootstrap</em> phase — before the plugin
 * object exists, let alone any module. A handler registered in {@code onEnable} is registered after that has
 * already happened, so it never runs: no warning, no exception, and the command simply does not exist while
 * {@code dispatchCommand} answers false as though nobody had heard of it.
 *
 * <p>That is not a theoretical footnote. Every chat button in RainsCore was dead on a real server for weeks
 * because of exactly this, and nothing below the server line could have caught it.
 *
 * <h2>Which means the commands exist before the modules do</h2>
 * So they are guarded: {@code ModuleRegistry.commands()} wraps every one, and a command whose module later
 * fails to start answers with one red line naming the module rather than a {@link NullPointerException}.
 */
public final class ModuleBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        // The modules have to be found here rather than in onEnable, because their commands are needed now.
        // Discovery is idempotent, so onEnable calling it again costs nothing.
        Modules.discover(getClass().getClassLoader());

        LifecycleEventManager<BootstrapContext> lifecycle = context.getLifecycleManager();
        lifecycle.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (ModuleCommand command : Modules.registry().commands()) {
                event.registrar().register(command.name(), command.description(),
                        command.aliases(), command.handler());
            }
            // The directory of everything on this server, which no single plugin can build — see
            // Core's CommandDirectory. Every standalone module plugin asks, because none of them can
            // know whether another is installed; Core hands it to the first and declines the rest, so
            // six installed plugins still mean one /commands showing one complete book.
            de.raindancer.core.platform.command.CoreCommands.commandList(event.registrar());
            // A chat button can only run a command, so one has to exist before RainsCore's own
            // ChatButtons is told to use it — see ModulePlugin.wireUpButtons(), which does the telling
            // once RainsCore is actually enabled. Registered here rather than left to whichever module
            // happens to want a button first, because a claim fee's [Accept] and a tpa request's are
            // the same mechanism and should not depend on load order to be wired up.
            de.raindancer.core.platform.command.CoreCommands.clickCallback(event.registrar());
            // The same reasoning, for a clicked player name: opening ProfileMenu is a command by
            // necessity too, and it has to exist before the first module offers a clickable name —
            // see ProfileLink and ProfileCommand for the mechanism itself.
            de.raindancer.core.platform.command.CoreCommands.profile(event.registrar());
            // Core deliberately registers nothing of its own — see CoreCommands' own class comment on
            // why — which means /settings does not exist on any server running these plugins unless
            // something registers it. Nothing here ever did: every module-standalone plugin, and the
            // bundle, reached every one of its own settings only through a module's own /<name> config
            // subcommand or Core's generic screen opened from one, and the bare command a player would
            // actually type for "everything on this server" was simply never wired up. Registered here,
            // once, the same way commandList and clickCallback already are, so every module-standalone
            // plugin and every bundle gets it for free regardless of which modules are actually present.
            de.raindancer.core.platform.command.CoreCommands.settings(event.registrar(), "settings");
        });
    }
}
