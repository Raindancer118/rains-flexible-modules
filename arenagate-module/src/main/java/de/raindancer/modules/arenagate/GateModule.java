package de.raindancer.modules.arenagate;

import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelListener;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.arenagate.listener.ArrivalListener;
import de.raindancer.modules.arenagate.service.EscrowService;
import de.raindancer.modules.arenagate.service.GateInvites;
import de.raindancer.modules.arenagate.service.GateNetwork;
import de.raindancer.modules.arenagate.service.GateService;
import de.raindancer.modules.arenagate.service.GearService;
import de.raindancer.modules.arenagate.store.EscrowDatabase;
import de.raindancer.modules.arenagate.store.EscrowStore;
import de.raindancer.modules.arenagate.util.PermissionNodes;

import java.util.List;

/**
 * The survival server's side of the arena: {@code /arena} and {@code /pvp}, stakes held in escrow here where the money
 * is, copies of gear for fights in one's own, and the link handlers that make invites work across the two servers.
 * Shipped through the standard wrapper this is {@code RainsArenaGate}; the arena itself is {@code RainsArena}.
 */
public final class GateModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("arenagate", "Arena Gate", "0.7.3")
            .describedAs("/arena and /pvp: off to the arena server, invites across both servers, stakes held here.")
            .by("Raindancer118");

    private GateNetwork network;
    private de.raindancer.modules.arenagate.service.GateConsents consents;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        SettingsStore<GateSettings> settings = context.settings(GateSettings.class, GateSettings.DEFAULTS);
        context.core().messages().defineFrom(GateModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(GateModule.class.getResourceAsStream("messages-serious.yml"));
        PermissionNodes.register(context.plugin().getServer());
        ProxyTransfer.register(context.plugin());

        ServerLink link = context.core().link().orElse(null);
        Travel travel = new Travel(context.plugin(), context.core().safety(), context.core().audit());
        context.listener(new TravelListener(travel, true));
        GateService gate = new GateService(context.plugin(), context.core().messages(), travel, settings.current(),
                link);

        if (link != null) {
            var database = context.core().databases().of(EscrowDatabase.NAME, EscrowDatabase.SCHEMA);
            EscrowService escrow = new EscrowService(Economies::current, new EscrowStore(database),
                    System::currentTimeMillis, id -> {
                        String name = context.plugin().getServer().getOfflinePlayer(id).getName();
                        return name == null ? id.toString().substring(0, 8) : name;
                    }, settings.current());
            settings.onChange(escrow::settings);
            consents = new de.raindancer.modules.arenagate.service.GateConsents(System::currentTimeMillis);
            escrow.consents(consents::takeStake);
            GearService gear = new GearService(context.plugin(), context.plugin().getServer(),
                    context.core().inventories(), ItemText.ofTheServer());
            network = new GateNetwork(context.plugin(), context.plugin().getServer(), context.core().messages(), link,
                    escrow, gear, new GateInvites(System::currentTimeMillis), gate, consents,
                    context.core().vanish()::isVanished);
            network.whereElse(name -> context.core().links().serverOf(name));
            network.listen();
            context.listener(new de.raindancer.modules.arenagate.listener.ReadyListener(context.plugin(), link));
            var sweep = Scheduling.asyncTimer(context.plugin(), 300, 300, task -> escrow.sweep());
            if (sweep != null) {
                context.closeWith(sweep::cancel);
            }
        } else {
            context.log().warn("There is no server link (RainsCore link.yml): /arena works, invites, queues, stakes "
                    + "and own gear do not.");
        }
        settings.onChange(gate::settings);

        GateServices services = new GateServices(context.plugin(), context.core().messages(), gate, network, consents,
                context.core()::playersElsewhere);
        context.listener(new ArrivalListener(services));
        GateCommands.ready(services);
        context.log().info("Arena gate is up: /arena goes to '{}', link {}.", settings.current().arenaOrDefault(),
                link == null ? "missing" : "present");
    }

    @Override
    public List<ModuleCommand> commands() {
        return GateCommands.declared();
    }

    @Override
    public void disable() {
        GateCommands.stopped();
        if (network != null) {
            network.stop();
        }
    }
}
