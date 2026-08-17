package de.raindancer.modules.essentials;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.essentials.listener.EssentialsSessionListener;
import de.raindancer.modules.essentials.service.AfkService;
import de.raindancer.modules.essentials.service.MessagingService;
import de.raindancer.modules.essentials.service.NicknameService;
import de.raindancer.modules.essentials.service.SpawnService;
import de.raindancer.modules.essentials.service.WelcomeService;
import de.raindancer.modules.essentials.store.EssentialsStore;
import de.raindancer.modules.essentials.store.NicknameBlocklist;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;

import java.util.List;

/**
 * The boring stuff, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsEssentials}, a plugin of its own.
 * Hosted inside another plugin it is one feature among several.
 *
 * <h2>What is deliberately not here</h2>
 * Death-location tracking and returning to it is tpa-module's {@code /back} — {@code Travel} records
 * every arrival's origin already, and a death is one more of those. Homes, warps and random teleports
 * are their own modules too. What is left, once all of that is taken out, is what this module is:
 * one shared place to send everybody, whether somebody is still there, private messages, and a name
 * to be called instead of your own.
 */
public final class EssentialsModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("essentials", "Essentials", "1.4.0")
            .describedAs("The boring stuff players immediately expect: /spawn, AFK, private "
                    + "messages, /seen, join and quit lines, and a nickname")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<EssentialsSettings> settings;

    private EssentialsStore store;
    private NicknameBlocklist blocklist;
    private Travel travel;
    private AfkService afk;
    private ScheduledTask afkSweeper;

    private EssentialsServices services;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(EssentialsSettings.class, EssentialsSettings.DEFAULTS);

        // The module's own wording, offered as a floor below anything the owner has written — never
        // Messages.load, which would throw away Core's own lines and every other module's with them.
        context.core().messages().defineFrom(
                EssentialsModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        int registered = de.raindancer.modules.essentials.util.PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        store = new EssentialsStore(context.dataFolder());
        store.load();

        blocklist = new NicknameBlocklist(context.dataFolder().resolve("blocklist.yml"),
                () -> EssentialsModule.class.getResourceAsStream("blocklist.yml"));
        blocklist.load();

        travel = new Travel(context.plugin(), context.core().safety(), context.core().audit());

        SpawnService spawn = new SpawnService(context.core().places(), travel,
                context.core().messages(), settings.current());
        afk = new AfkService(context.core().identities(), context.core().messages(),
                context.chat(), settings.current());
        MessagingService messaging = new MessagingService(store, context.core().messages(),
                context.chat(), context.core().vanish(), settings.current());
        NicknameService nicknames = new NicknameService(store, blocklist,
                context.core().identities(), context.core().messages(), context.chat(), server,
                context.core().punishments(), context.core().audit(), settings.current());
        WelcomeService welcome = new WelcomeService(context.core().messages(), context.chat(),
                settings.current());

        services = new EssentialsServices(context.plugin(), server, context.core(), log,
                context.core().messages(), context.chat(), context.chat().brand(),
                settings::current, store, blocklist, spawn, afk, messaging, nicknames, welcome);

        settings.onChange(fresh -> {
            spawn.settings(fresh);
            afk.settings(fresh);
            messaging.settings(fresh);
            nicknames.settings(fresh);
            welcome.settings(fresh);
        });

        context.listener(new EssentialsSessionListener(services));

        // Once a second: frequent enough that nobody stays AFK for long after they come back without
        // anything else noticing, cheap enough that it costs nothing on a server with hundreds online.
        afkSweeper = Scheduling.globalTimer(context.plugin(), 20L, 20L,
                ignored -> afk.sweep(server.getOnlinePlayers()));

        // The commands were registered during bootstrap, long before any of this existed, and have
        // been answering "not started yet" until now. See EssentialsCommands.
        EssentialsCommands.ready(services);

        log.info("Essentials are up: {}s to /spawn, AFK after {}s, {} player(s) nicknamed, "
                        + "{} name(s) blocklisted.",
                settings.current().spawnWarmup(), settings.current().afkTimeout(),
                store == null ? 0 : store.nicknameCount(), blocklist.enabledNameCount());
    }

    @Override
    public List<ModuleCommand> commands() {
        return EssentialsCommands.declared();
    }

    @Override
    public void disable() {
        EssentialsCommands.stopped();
        if (afkSweeper != null) {
            afkSweeper.cancel();
        }
        if (travel != null) {
            travel.clear();
        }
        if (store != null) {
            store.flush();
        }
        // The listeners are unregistered by the context, in the reverse order they were registered.
    }
}
