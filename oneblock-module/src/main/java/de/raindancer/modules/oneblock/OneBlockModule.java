package de.raindancer.modules.oneblock;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.proxy.ProxyTransfer;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.oneblock.listener.ArrivalListener;
import de.raindancer.modules.oneblock.rules.InviteRule;
import de.raindancer.modules.oneblock.service.InviteService;
import de.raindancer.modules.oneblock.service.IslandEndpoint;
import de.raindancer.modules.oneblock.service.IslandHost;
import de.raindancer.modules.oneblock.service.IslandHosts;
import de.raindancer.modules.oneblock.service.IslandsGateway;
import de.raindancer.modules.oneblock.service.LocalGateway;
import de.raindancer.modules.oneblock.service.NetworkDelivery;
import de.raindancer.modules.oneblock.service.RemoteGateway;
import de.raindancer.modules.oneblock.store.InviteBook;
import de.raindancer.modules.oneblock.util.PermissionNodes;
import org.bukkit.Server;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * OneBlock islands across the network. The islands themselves are BentoBox and AOneBlock on their own server; this
 * module runs on every server and carries {@code /island} — going there, and team and guest invites — between them.
 *
 * <p>Two shapes from one jar: with BentoBox installed this is the island server, which keeps the invites and answers
 * the others over every link it has; without, it asks the island server over the link named in the settings.
 */
public final class OneBlockModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("oneblock", "OneBlock", "0.1.0")
            .describedAs("OneBlock islands on their own server, with /island and invites from anywhere on the network.")
            .by("Raindancer118");

    /** BentoBox's member rank; the island server reads the real one, the rule only needs it as a floor. */
    private static final int MEMBER_RANK = 500;
    private static final long SWEEP_TICKS = 100L;

    private LogChannel log;
    private IslandEndpoint endpoint;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        SettingsStore<OneBlockSettings> settings = context.settings(OneBlockSettings.class, OneBlockSettings.DEFAULTS);
        context.core().messages().defineFrom(OneBlockModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(OneBlockModule.class.getResourceAsStream("messages-serious.yml"));
        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }
        ProxyTransfer.register(context.plugin());

        Executor onServer = task -> Scheduling.global(context.plugin(), task);
        NetworkDelivery delivery = new NetworkDelivery(context.plugin(), server, context.core().messages(),
                context.core()::links, player -> context.core().vanish().isVanished(player.getUniqueId()));
        Optional<IslandHost> host = IslandHosts.on(server, log, () -> settings.current().gameMode());

        IslandsGateway gateway;
        Optional<InviteService> invites = Optional.empty();
        if (host.isPresent()) {
            InviteService service = new InviteService(host.get(), new InviteBook(), new InviteRule(MEMBER_RANK), delivery,
                    delivery::resolve, onServer, System::currentTimeMillis, settings.current());
            settings.onChange(service::settings);
            invites = Optional.of(service);
            gateway = new LocalGateway(service, host.get(), onServer);
            List<ServerLink> links = new ArrayList<>(context.core().links().all().values());
            endpoint = new IslandEndpoint(service, links);
            endpoint.listen();
            var timer = Scheduling.globalTimer(context.plugin(), SWEEP_TICKS, SWEEP_TICKS, task -> service.sweep());
            if (timer != null) {
                context.closeWith(timer::cancel);
            }
            log.info("This is the island server ({}): answering invites over {} link(s).", settings.current().gameMode(),
                    links.size());
            if (links.isEmpty()) {
                log.warn("No links in RainsCore's link.yml — invites work only between players on this server.");
            }
        } else {
            gateway = new RemoteGateway(() -> context.core().links().named(settings.current().islandServer()));
            if (context.core().links().named(settings.current().islandServer()).isEmpty()) {
                log.warn("No link called '{}' in RainsCore's link.yml — /island still sends people to '{}', but "
                        + "invites from here are refused until there is one.", settings.current().islandServer(),
                        settings.current().proxyServer());
            }
        }

        OneBlockServices services = new OneBlockServices(context.plugin(), server, log, context.core().messages(),
                context.core(), settings::current, gateway, invites);
        if (invites.isPresent()) {
            context.listener(new ArrivalListener(services));
        }
        OneBlockCommands.ready(services);
    }

    @Override
    public List<ModuleCommand> commands() {
        return OneBlockCommands.declared();
    }

    @Override
    public void disable() {
        OneBlockCommands.stopped();
        if (endpoint != null) {
            endpoint.stop();
            endpoint = null;
        }
    }
}
