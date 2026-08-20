package de.raindancer.modules.xaeromap;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.xaeromap.claims.ClaimIntegration;
import de.raindancer.modules.xaeromap.claims.ClaimSource;
import de.raindancer.modules.xaeromap.listener.ChannelListener;
import de.raindancer.modules.xaeromap.listener.ClaimChannelListener;
import de.raindancer.modules.xaeromap.listener.PlayerLeaveListener;
import de.raindancer.modules.xaeromap.listener.WorldChangeListener;
import de.raindancer.modules.xaeromap.model.OpacPackets;
import de.raindancer.modules.xaeromap.model.XaeroWorldId;
import de.raindancer.modules.xaeromap.rules.RefreshDueRule;
import de.raindancer.modules.xaeromap.service.ClaimSyncService;
import de.raindancer.modules.xaeromap.service.RefreshService;
import de.raindancer.modules.xaeromap.service.WorldIdService;
import de.raindancer.modules.xaeromap.store.ClaimMirror;
import de.raindancer.modules.xaeromap.store.SyncIndexTable;
import de.raindancer.modules.xaeromap.util.PermissionNodes;
import de.raindancer.modules.xaeromap.util.Wire;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.List;

/**
 * Server-side support for Xaero's Minimap and Xaero's World Map, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsXaeroMap}. Two things, and a server can
 * have either without the other:
 *
 * <ul>
 *   <li><b>A map per world.</b> Told nothing, both mods file every world on a Bukkit server under one
 *       shared map keyed on the server address — so the nether is drawn over the overworld and a farm
 *       world overwrites the survival map. One packet per world change fixes it, and it is the half of
 *       this module that needs no claims plugin at all.</li>
 *   <li><b>Claims on the map.</b> Neither mod has any way for a server to hand it a coloured region;
 *       what both of them <em>do</em> have is a reader for Open Parties and Claims' claim protocol. So
 *       this server's claims are sent in that format, and appear on the client's own map with their
 *       names and their own colours, with no client mod written by us. What a player does need is Open
 *       Parties and Claims beside their minimap — it is the mod that reads this protocol, and the one
 *       Xaero's maps draw claims out of. A player without it is simply never sent any, which costs them
 *       nothing and is the same silence as having no map mod at all.</li>
 * </ul>
 *
 * <h2>Read-only, on purpose</h2>
 * The map draws claims; it cannot make them. The mod's own claim requests are ignored rather than
 * answered — see {@code ClaimSyncService} — because answering them would be a second way to claim land
 * on this server, one that knows nothing about who may claim, what it costs, or how large a claim may
 * be.
 */
public final class XaeroMapModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("xaeromap", "Xaero's Map support", "1.0.0")
            .describedAs("Gives every world its own map on Xaero's Minimap and World Map, and draws "
                    + "this server's claims on them.")
            .by("Raindancer118");

    /** The refresh clock is asked once a second; {@code RefreshDueRule} decides whether it does work. */
    private static final long TICK_PERIOD_TICKS = 20L;

    private LogChannel log;
    private SettingsStore<XaeroMapSettings> settings;
    private ClaimChannelListener incoming;
    private Plugin plugin;

    /**
     * Where claims come from, looked up once and then held.
     *
     * <p>Resolved lazily rather than in {@link #enable}: a claims plugin in its own jar may enable
     * after this one, and a lookup done at enable time would answer "no claims plugin" for the rest of
     * the server's life. Every path that needs claims goes through this, so the first one to run after
     * claims is up finds it.
     */
    private volatile ClaimSource claims = ClaimSource.NONE;
    private volatile boolean claimsResolved;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        plugin = context.plugin();
        Server server = plugin.getServer();
        settings = context.settings(XaeroMapSettings.class, XaeroMapSettings.DEFAULTS);

        context.core().messages().defineFrom(
                XaeroMapModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        // Outgoing on all three, incoming on the claims one only: the two Xaero channels are ours to
        // talk on and nothing ever answers, while the claims channel is a conversation. Registering an
        // incoming channel we never read would swallow messages another plugin is listening for.
        server.getMessenger().registerOutgoingPluginChannel(plugin, XaeroWorldId.MINIMAP_CHANNEL);
        server.getMessenger().registerOutgoingPluginChannel(plugin, XaeroWorldId.WORLDMAP_CHANNEL);
        server.getMessenger().registerOutgoingPluginChannel(plugin, OpacPackets.CHANNEL);

        Wire wire = Wire.through(plugin);
        SyncIndexTable indices = new SyncIndexTable();
        ClaimMirror mirror = new ClaimMirror();

        WorldIdService worldIds = new WorldIdService(wire, settings.current());
        ClaimSyncService sync = new ClaimSyncService(wire, this::claims, indices, mirror, log,
                settings.current());
        RefreshService refresh = new RefreshService(sync, new RefreshDueRule(), settings.current());

        settings.onChange(worldIds::settings);
        settings.onChange(sync::settings);
        settings.onChange(refresh::settings);
        // A changed audience or a changed coverage threshold makes every client's picture wrong in a way
        // a difference cannot express — a claim that was visible and now is not was never "changed", it
        // simply stops appearing in the diff. So a reload drops every mirror, and the next refresh sends
        // each client the whole picture again.
        settings.onChange(changed -> mirror.forgetEverybody());

        incoming = new ClaimChannelListener(sync);
        server.getMessenger().registerIncomingPluginChannel(plugin, OpacPackets.CHANNEL, incoming);

        context.listener(new ChannelListener(worldIds, sync));
        context.listener(new WorldChangeListener(worldIds));
        context.listener(new PlayerLeaveListener(sync));

        XaeroMapCommands.ready(new XaeroMapServices(plugin, server, log,
                context.core().messages(), context.chat().brand(), context.core(),
                settings::current, settings, this::claims, indices, worldIds, sync));

        var timer = Scheduling.globalTimer(plugin, TICK_PERIOD_TICKS, TICK_PERIOD_TICKS,
                task -> refresh.tick(server.getOnlinePlayers(), Instant.now()));
        if (timer != null) {
            context.closeWith(timer::cancel);
        }

        log.info("Map support is up: per-world maps {}, claims {}.",
                settings.current().worldIds() ? "on" : "off",
                settings.current().claims() ? "on, refreshed every "
                        + settings.current().refresh().toSeconds() + "s" : "off");
    }

    /** The claim source, resolving it the first time anything asks. */
    private ClaimSource claims() {
        if (!claimsResolved) {
            synchronized (this) {
                if (!claimsResolved) {
                    claims = ClaimIntegration.trySource(plugin == null ? null : plugin.getServer(), log);
                    // Only settled once something is actually there. A server whose claims plugin has
                    // not enabled yet is asked again next time rather than written off.
                    claimsResolved = claims.available();
                }
            }
        }
        return claims;
    }

    @Override
    public List<ModuleCommand> commands() {
        return XaeroMapCommands.declared();
    }

    @Override
    public void disable() {
        XaeroMapCommands.stopped();
        if (plugin != null) {
            // Unregistered by hand because it was registered by hand: the messenger is not the event
            // bus, so the host's own unwinding does not reach it. Left registered, a reload leaves a
            // listener holding a dead module's services and answering clients with them.
            //
            // Each channel by name, never the whole-plugin overload: hosted inside a larger plugin,
            // that would also close the channels of every *other* module sharing this host — a module
            // that is still running, quietly stopping being able to talk to anybody.
            var messenger = plugin.getServer().getMessenger();
            messenger.unregisterOutgoingPluginChannel(plugin, XaeroWorldId.MINIMAP_CHANNEL);
            messenger.unregisterOutgoingPluginChannel(plugin, XaeroWorldId.WORLDMAP_CHANNEL);
            messenger.unregisterOutgoingPluginChannel(plugin, OpacPackets.CHANNEL);
            if (incoming != null) {
                messenger.unregisterIncomingPluginChannel(plugin, OpacPackets.CHANNEL, incoming);
            }
        }
        incoming = null;
    }
}
