package de.raindancer.modules.arena;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.arena.listener.PresenceListener;
import de.raindancer.modules.arena.listener.ProtectionListener;
import de.raindancer.modules.arena.listener.SafetyListener;
import de.raindancer.modules.arena.rules.FightRule;
import de.raindancer.modules.arena.rules.ProtectionRule;
import de.raindancer.modules.arena.service.ArenaService;
import de.raindancer.modules.arena.service.ArenaWorldService;
import de.raindancer.modules.arena.store.LayoutBook;
import de.raindancer.modules.arena.store.LayoutStore;
import de.raindancer.modules.arena.util.PermissionNodes;
import org.bukkit.Server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The arena server: arena worlds built from schematics, a hub, pits to fight in — on a server of its own behind the
 * network's proxy, so nothing anybody owns on the survival server is ever at stake here.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsArena}. It belongs on the arena server only; the survival
 * server runs the gate that sends people here.
 */
public final class ArenaModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("arena", "Arena", "0.1.0")
            .describedAs("A PvP arena server: worlds built from schematics, a hub, and pits to fight in.")
            .by("Raindancer118");

    private LogChannel log;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        SettingsStore<ArenaSettings> settings = context.settings(ArenaSettings.class, ArenaSettings.DEFAULTS);

        context.core().messages().defineFrom(ArenaModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(ArenaModule.class.getResourceAsStream("messages-serious.yml"));

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }
        ProxyTransfer.register(context.plugin());

        Path maps = context.dataFolder().resolve("maps");
        try {
            Files.createDirectories(maps);
        } catch (IOException failure) {
            log.error("The maps folder could not be made: {}", failure.getMessage());
        }

        LayoutBook layout = new LayoutBook(new LayoutStore(context.dataFolder().resolve("layout.yml")));
        ArenaWorldService worlds = new ArenaWorldService(context.plugin(), log, maps, settings.current());
        ArenaService arena = new ArenaService(context.plugin(), server, context.core().messages(),
                context.core().effects(), worlds, layout, settings.current());
        settings.onChange(fresh -> {
            worlds.settings(fresh);
            arena.settings(fresh);
            Scheduling.global(context.plugin(), () -> worlds.loadAll(layout.current()));
        });

        // No fight runs yet in this version, so nobody is in one: the hub's "nobody is hurt" holds everywhere.
        ArenaServices services = new ArenaServices(context.plugin(), server, log, context.core().messages(),
                context.core(), settings::current, arena, new ProtectionRule(), player -> false);

        FightRule fights = new FightRule();
        context.core().combat().alsoAsk(context.plugin().getName(), attack ->
                fights.verdict(attack, arena::isArenaWorld, (attacker, victim) -> services.fighting(attacker)
                        && services.fighting(victim)));

        Scheduling.global(context.plugin(), () -> {
            worlds.loadAll(layout.current());
            log.info("Arena is up: {} world(s), {} pit(s), hub {}.", layout.current().worlds().size(),
                    layout.current().pits().size(), layout.current().hub().isPresent() ? "set" : "not set");
        });

        context.listener(new PresenceListener(services));
        context.listener(new ProtectionListener(services));
        context.listener(new SafetyListener(services));
        ArenaCommands.ready(services);
    }

    @Override
    public List<ModuleCommand> commands() {
        return ArenaCommands.declared();
    }

    @Override
    public void disable() {
        ArenaCommands.stopped();
    }
}
