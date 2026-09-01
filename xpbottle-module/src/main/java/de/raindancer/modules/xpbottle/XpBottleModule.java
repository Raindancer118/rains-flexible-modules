package de.raindancer.modules.xpbottle;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.xpbottle.listener.BottleUseListener;
import de.raindancer.modules.xpbottle.listener.SiphonHoldListener;
import de.raindancer.modules.xpbottle.rules.FillAmountRule;
import de.raindancer.modules.xpbottle.rules.SiphonReachRule;
import de.raindancer.modules.xpbottle.screen.XpBottleRootMenu;
import de.raindancer.modules.xpbottle.service.BottleForge;
import de.raindancer.modules.xpbottle.service.BottlingService;
import de.raindancer.modules.xpbottle.service.SiphonService;
import de.raindancer.modules.xpbottle.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Experience, taken out of a player and put in a bottle.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsXPBottles}. A plain glass bottle,
 * right clicked in the air, draws what its holder is carrying into a bottle o' enchanting holding
 * exactly that many points; right clicking that pours it back. A siphon bottle — a potion-shaped
 * item with tiers, given out by staff — is <em>held</em> down instead, and pulls loose experience
 * orbs off the ground around its holder, falling back to their own bar when there are none in reach.
 *
 * <h2>The one invariant</h2>
 * Points in equal points out. Never levels, never a rounded fraction of a level, and never a bottle
 * whose contents came from anywhere but somewhere they were taken from. Everything else here is
 * arrangement; that is the part that would be a duplication bug if it were wrong.
 */
public final class XpBottleModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("xpbottle", "XP Bottles", "1.0.0")
            .describedAs("Draw experience into a bottle — your own with a plain glass bottle, or "
                    + "loose orbs off the ground with a siphon bottle held down.")
            .by("Raindancer118");

    /**
     * How often the siphon timer runs.
     *
     * <p>Four ticks rather than one: a draw is a fifth of a second's worth of points at a time,
     * which still reads as continuous, and the scan for nearby orbs — the expensive half — happens a
     * quarter as often. Every rate in the settings is per second and is divided down by this, so
     * changing it changes nothing an owner configured.
     */
    private static final long TICK_PERIOD_TICKS = 4L;

    private LogChannel log;
    private SettingsStore<XpBottleSettings> settings;
    private XpBottleServices services;
    private SiphonService siphon;
    private Server server;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        server = context.plugin().getServer();
        settings = context.settings(XpBottleSettings.class, XpBottleSettings.DEFAULTS);

        context.core().messages().defineFrom(
                XpBottleModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        FillAmountRule fill = new FillAmountRule();
        SiphonReachRule reach = new SiphonReachRule();
        BottleForge forge = new BottleForge(settings.current());
        BottlingService bottling = new BottlingService(context.core().messages(),
                context.core().effects(), fill, forge, settings.current());
        siphon = new SiphonService(context.plugin(), context.core().messages(),
                context.core().effects(), context.core().actionBars(), fill, reach, forge, bottling,
                settings.current());

        settings.onChange(forge::settings);
        settings.onChange(bottling::settings);
        settings.onChange(siphon::settings);

        services = new XpBottleServices(context.plugin(), server, log, context.core().messages(),
                context.chat().brand(), context.core(), settings::current, settings,
                forge, bottling, siphon, new LiveScreens());

        context.listener(new BottleUseListener(services));
        context.listener(new SiphonHoldListener(services));

        XpBottleCommands.ready(services);

        var timer = Scheduling.globalTimer(context.plugin(), TICK_PERIOD_TICKS, TICK_PERIOD_TICKS,
                task -> siphon.tick(server.getOnlinePlayers(), TICK_PERIOD_TICKS));
        if (timer != null) {
            context.closeWith(timer::cancel);
        }

        XpBottleSettings live = settings.current();
        log.info("XP bottles are up: a plain bottle holds {}, a tier {} siphon holds {} and "
                        + "reaches {} blocks.", live.capacityFor(0), live.highestTierClamped(),
                live.capacityFor(live.highestTierClamped()),
                live.reachFor(live.highestTierClamped()));
    }

    /** Opening this module's screens, without the command knowing the menu classes. */
    private final class LiveScreens implements IXpBottleScreensOpener {

        @Override
        public void root(Player viewer) {
            // null parent: an entry point from a command, and Core draws no Back button on a
            // parentless menu — right, since there is nowhere behind it to go back to.
            new XpBottleRootMenu(services, viewer).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return XpBottleCommands.declared();
    }

    /**
     * Stopping writes every half-drawn bottle back before anything else is torn down.
     *
     * <p>A siphon holds what it has pulled in memory until the draw ends — so a module reloaded
     * while somebody was mid-draw would be experience taken out of the world and given to nobody.
     * This is that door, and it is the reason {@code SiphonService#flushAll} exists at all.
     */
    @Override
    public void disable() {
        if (siphon != null && server != null) {
            int written = siphon.flushAll(server.getOnlinePlayers());
            if (written > 0) {
                log.info("{} point(s) written back into bottles that were still being drawn.",
                        written);
            }
        }
        XpBottleCommands.stopped();
    }
}
