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
        });
    }
}
