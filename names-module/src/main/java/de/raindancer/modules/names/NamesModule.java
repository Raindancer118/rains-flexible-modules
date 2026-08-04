package de.raindancer.modules.names;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.names.listener.CauldronListener;
import de.raindancer.modules.names.listener.CraftingListener;
import de.raindancer.modules.names.listener.MobNameListener;
import de.raindancer.modules.names.screen.PaletteMenu;
import de.raindancer.modules.names.service.CraftService;
import de.raindancer.modules.names.service.MobNameService;
import de.raindancer.modules.names.service.ReloadService;
import de.raindancer.modules.names.service.WashService;
import de.raindancer.modules.names.store.Palette;
import de.raindancer.modules.names.store.PaletteFile;
import de.raindancer.modules.names.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Coloured names, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsColouredNames}, a plugin of its own.
 * Hosted inside {@code RainsSMPCore} it is one feature among several. The code below cannot tell which,
 * and that is the whole point of the arrangement.
 *
 * <h2>What enabling actually does</h2>
 * Reads the palette out of {@code config.yml} — writing the shipped one in if the file has never had one
 * — and registers three listeners: the crafting grid, the water cauldron and the mob name. Nothing is
 * loaded into memory beyond that, because there is nothing to load: a styled name tag <em>is</em> the
 * record, held in its own persistent data, so a server that removes this module keeps every tag anybody
 * has dyed and gets them back the day it is installed again.
 *
 * <h2>Why none of this is a Bukkit recipe</h2>
 * See {@code rules.CraftRule}. Two of the four crafts cannot be expressed as one, and registering the
 * other two would buy a recipe-book entry at the price of two code paths that can disagree about who
 * pays for an item.
 *
 * <h2>What is deliberately Core's</h2>
 * The scheduling (Folia-safe, per entity), the settings — file, comments, validation and the
 * {@code /settings} screens all derived from {@link NamesSettings} — the wording, the menu, the buttons
 * and the atomic write behind the palette. What is left here is what a name tag means, which is the only
 * part another plugin would not want.
 */
public final class NamesModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("names", "Coloured names", "2.0.1")
            .describedAs("Dye a name tag, then craft it with an item to paint that item's name — "
                    + "gradients included")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<NamesSettings> settings;
    private PaletteFile palette;

    private CraftService crafting;
    private WashService washing;
    private MobNameService mobNames;
    private ReloadService reloading;

    private NamesServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(NamesSettings.class, NamesSettings.DEFAULTS);

        // The module's own wording, offered as a floor below anything the owner has written. Not
        // Messages.load: there is one Messages on the server and it is Core's, so loading would throw
        // away Core's own lines and every other module's with them.
        //
        // Looked up beside this class rather than at "/messages.yml": RainsCore ships one at the root
        // of its own jar and `join-classpath: true` puts it on this module's classpath, so a root
        // lookup is a race between two files with the same name.
        //
        // Signed with this module's own brand, so its sentences say what they came from rather than
        // whichever module plugin happened to start last.
        context.core().messages().defineFrom(
                NamesModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        // Before anything asks. An unregistered permission resolves to "operators only", which would
        // refuse the manual to every ordinary player on the server.
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        // The palette shares config.yml with the settings above. Safe in both directions: Core keeps
        // the keys its schema does not know, and nothing here writes a key the schema owns.
        palette = new PaletteFile(context.dataFolder().resolve("config.yml"));
        palette.load(warning -> log.warn("{}", warning));

        crafting = new CraftService(context.plugin(), palette::current, settings.current());
        washing = new WashService(palette::current, settings.current());
        mobNames = new MobNameService(context.plugin(), settings.current());
        reloading = new ReloadService(settings, palette, log);

        services = new NamesServices(context.plugin(), server, log, context.core().messages(),
                context.chat(), context.chat().brand(),
                palette::current, settings::current,
                crafting, washing, mobNames, reloading,
                new LiveScreens());

        // Every setting is a snapshot, so a reload hands each service a fresh one. Missing one of these
        // is a subsystem that keeps yesterday's numbers until the next restart, which is the sort of
        // defect that gets reported as "the config does not work".
        settings.onChange(fresh -> {
            crafting.settings(fresh);
            washing.settings(fresh);
            mobNames.settings(fresh);
            reloading.settings(fresh);
        });

        context.listener(new CraftingListener(services));
        context.listener(new CauldronListener(services));
        context.listener(new MobNameListener(services));

        // The command was registered during bootstrap, long before any of this existed, and has been
        // answering "not started yet" until now. See NamesCommands.
        NamesCommands.ready(services);

        Palette loaded = palette.current();
        log.info("Coloured names is up: {} reagent(s), up to {} stops.",
                loaded.reagents().size(), settings.current().stops());
        if (loaded.isEmpty()) {
            log.warn("Nothing dyes a name tag: colours, decorations and shades are all empty in {}.",
                    palette.file());
        }
    }

    /**
     * Opening the screens, which is the only thing in the module that knows the menu classes exist.
     *
     * <p>An inner class rather than a lambda at the construction site: a new screen is one method here
     * rather than one more argument there.
     */
    private final class LiveScreens implements INamesScreensOpener {

        @Override
        public void manual(Player viewer) {
            // Parent null: this is an entry point from a command, and Core draws no Back button on a
            // parentless menu — which is right, since there is nothing behind it to go back to.
            new PaletteMenu(services, viewer, null).open();
        }
    }

    /**
     * The command, declared at bootstrap.
     *
     * <p>Paper wants it before anything is enabled, so it is built pointing at a supplier that is filled
     * in when this module starts. Until then the host's guard answers with one line saying so.
     */
    @Override
    public List<ModuleCommand> commands() {
        return NamesCommands.declared();
    }

    @Override
    public void disable() {
        NamesCommands.stopped();
        // Nothing to write. Every style this module has ever produced is on an item in somebody's
        // inventory, which the server saves; there is no registry here to lose.
        //
        // The listeners are unregistered by the context, in the reverse order they were registered —
        // see ModuleContext.closeWith.
    }

    /** What dyes a name tag on this server, for a host that wants to show it. */
    public Palette palette() {
        return palette == null ? Palette.defaults() : palette.current();
    }
}
