package de.raindancer.modules.wrapper;

import de.raindancer.core.RainsCore;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.LiveModuleSession;
import de.raindancer.modules.api.ModuleHost;
import de.raindancer.modules.api.ModuleHosts;
import de.raindancer.modules.api.ModuleState;
import de.raindancer.modules.api.Modules;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The plugin a module ships as, when it ships alone.
 *
 * <h2>What a standalone module has to write</h2>
 * Nothing. A {@code paper-plugin.yml} naming this class — which {@link StandaloneDescriptor} generates — plus
 * the module on the classpath, and it is a plugin. That is the half of {@code The Idea.md} this file exists for:
 * the same module is a feature of {@code RainsSMPCore} and a plugin of its own, without being written twice or
 * having a plugin class per module.
 *
 * <h2>What it actually does</h2>
 * Finds the modules in its own jar, gives each a place to run, and starts them. All the interesting behaviour —
 * the ordering, the failure isolation, the unwinding — is {@code ModuleRegistry}'s, and is the same code the
 * embedded host runs. This is the shell.
 */
public final class ModulePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // Already discovered by the bootstrapper, which had to look in order to register the commands. Calling
        // again is free: discovery is idempotent, and a jar with no bootstrapper still works this way.
        Modules.discover(getClass().getClassLoader());

        if (!RainsCore.isAvailable()) {
            // Should be unreachable — the descriptor declares RainsCore as required — but a hand-edited
            // paper-plugin.yml is a thing that happens, and this is a sentence rather than a stack trace forty
            // frames into a module.
            getSLF4JLogger().error("RainsCore is not running, so nothing here can start. Check that "
                    + "paper-plugin.yml declares it under dependencies.server with join-classpath: true.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ModuleHost host = ModuleHosts.standalone(this, Modules.registry());
        Modules.registry().enableAll(module -> new LiveModuleSession(host, module));

        brandTheMessages();
        wireUpButtons();
        reportTheCommands();
        report();
    }

    @Override
    public void onDisable() {
        // Stops everything that started, newest first, and unwinds each one — then forgets the registry, so a
        // reload starts from nothing rather than from whatever the last run left behind.
        Modules.shutdown();
    }

    /**
     * Tells Core what this plugin is called, so its messages say so.
     *
     * <p>Without it every line went out as <code>[Core]</code>. {@code prefixed()} reads the {@code prefix}
     * message key, of which there is one per server: RainsCore's bundled wording defines that value, a module's
     * wording arrives as a floor, and a floor loses to the bundled file. So a module could ship a perfectly good
     * gradient prefix and never be able to use it — the tag was on every window title and nowhere in chat.
     *
     * <p>The name is the <b>module's</b> when there is exactly one, and the plugin's otherwise. A plugin wrapping
     * one module <em>is</em> that module as far as anybody using it is concerned, and "Claims" reads better in
     * front of every line than "RainsExtendedClaims" does.
     *
     * <p>Asked rather than copied, so an owner who renames the tag in Core's settings is believed without a
     * restart, and so {@link de.raindancer.core.ui.chat.Brand} keeps deciding whether the tag is shown at all.
     */
    private void brandTheMessages() {
        var running = Modules.registry().enabled();
        String name = running.size() == 1
                ? running.get(0).info().name()
                : getName();
        var brand = RainsCore.get().chatFor(name).brand();
        RainsCore.get().messages().prefixFrom(brand::chatPrefix);
    }

    /**
     * Tells Core's {@code ChatButtons} that its callback command actually exists now.
     *
     * <p>{@code ModuleBootstrap} registers the {@code rcclick} command during the bootstrap phase, before
     * this plugin — or RainsCore — has enabled. Buttons render as readable text with no click until
     * something tells {@code ChatButtons} the name it was registered under; without this line every
     * {@code [Accept]}/{@code [Deny]} on the server is dead and says nothing about why, which is exactly
     * the failure {@code ModuleBootstrap}'s own class comment describes happening for weeks.
     */
    private void wireUpButtons() {
        RainsCore.get().buttons().callbackCommand("rcclick");
    }

    /**
     * Tells Core's directory what this plugin offers, so {@code /commands} knows about it.
     *
     * <p>Derived from what the modules already declared rather than written out again. That is the
     * whole design: a module says its commands once, in the list Paper registers from, and the book
     * is generated from the same list. There is no second place to keep in step and therefore no way
     * for a new command to be missing from the book — which is what a hand-maintained list would
     * guarantee, on the same schedule as everything else that has to be remembered here.
     *
     * <p>What a module does not declare — the sentence and the options — falls back to the
     * description Paper already shows in its own help, which is a worse entry than a written one and
     * a far better one than none.
     */
    private void reportTheCommands() {
        var directory = RainsCore.get().commands();
        String plugin = Modules.registry().enabled().size() == 1
                ? Modules.registry().enabled().get(0).info().name()
                : getName();

        for (FlexModule module : Modules.registry().enabled()) {
            for (ModuleCommand command : module.commands()) {
                directory.declare(new de.raindancer.core.platform.command.CommandNote(
                        plugin, command.name(), command.description(), command.options(),
                        command.permission()));
            }
        }
    }

    /**
     * One line per module, and every problem out loud.
     *
     * <p>A module that did not start is the thing an operator most needs to know and the thing least likely to
     * be noticed: the server comes up, the plugin says enabled, and one feature is simply absent.
     */
    private void report() {
        for (FlexModule module : Modules.registry().declared()) {
            String id = module.info().id();
            ModuleState state = Modules.registry().stateOf(id);
            String because = Modules.registry().reasonFor(id).map(reason -> " — " + reason).orElse("");
            switch (state) {
                case ENABLED -> getSLF4JLogger().info("{} is running.", module.info());
                case SKIPPED -> getSLF4JLogger().warn("{} was not started{}", module.info(), because);
                case FAILED -> getSLF4JLogger().error("{} failed to start{}", module.info(), because);
                default -> getSLF4JLogger().warn("{} is {}{}", module.info(), state, because);
            }
        }
        Modules.registry().problems().forEach(getSLF4JLogger()::warn);

        if (Modules.registry().declared().isEmpty()) {
            // A jar with no modules is a build mistake — a missing dependency, or a shade that dropped the
            // service file. Saying so beats a plugin that starts and does nothing.
            getSLF4JLogger().error("This jar contains no modules. Either the module dependency is missing, "
                    + "or the shade dropped META-INF/services/de.raindancer.modules.api.FlexModule.");
        }
    }
}
