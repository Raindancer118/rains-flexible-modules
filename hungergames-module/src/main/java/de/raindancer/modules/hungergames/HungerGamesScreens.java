package de.raindancer.modules.hungergames;

import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.hungergames.model.BorderConflict;
import de.raindancer.modules.hungergames.model.BorderMath;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.BorderSettings;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.screen.AdminMenu;
import de.raindancer.modules.hungergames.screen.BorderConflictMenu;
import de.raindancer.modules.hungergames.screen.GamemasterMenu;
import de.raindancer.modules.hungergames.screen.ShopMenu;
import de.raindancer.modules.hungergames.screen.SpectateMenu;
import de.raindancer.modules.hungergames.screen.TeamsMenu;
import de.raindancer.modules.hungergames.service.DeathmatchService;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.service.MannequinSimService;
import de.raindancer.modules.hungergames.service.MonsterWaveService;
import de.raindancer.modules.hungergames.service.PreflightCheckService;
import de.raindancer.modules.hungergames.service.RoundLogService;
import de.raindancer.modules.hungergames.service.SpectatorService;
import de.raindancer.modules.hungergames.service.SponsorTokenService;
import de.raindancer.modules.hungergames.service.SupplyDropService;
import de.raindancer.modules.hungergames.service.VirtualTime;
import de.raindancer.modules.hungergames.service.AnnouncementService;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.SponsorShopStore;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import org.bukkit.entity.Player;
import de.raindancer.core.content.items.ItemFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The five doors into this module's screens, and the services behind each of them.
 *
 * <h2>Why this class is not simply the menus themselves</h2>
 * Because a command cannot construct a menu. {@code HungerGamesCommands} is built during Paper's bootstrap,
 * before the plugin object exists — a menu needs a viewer, a brand and about a dozen services, none of which
 * are there yet. {@link IHungerGamesScreensOpener} is the seam that lets a command say <em>which</em> page it
 * wants without naming a class, and this is what fills that seam in once the module is actually up.
 *
 * <h2>Why it takes so many collaborators, and why that is not a god object</h2>
 * Seventeen, and every one of them is here because a page needs it. That looks like the {@code getInstance()}
 * this module was ported away from, and the difference is worth being precise about:
 *
 * <ul>
 *   <li><b>Nothing reaches back.</b> This holds services; the services do not hold this. There is no cycle,
 *       so any one of them can still be constructed and tested with nothing else present.</li>
 *   <li><b>Nothing here is static.</b> Two of these can exist at once — which is exactly what a test does.</li>
 *   <li><b>The menus keep their own constructors.</b> {@code AdminMenu} still asks for the sixteen things it
 *       needs, one by one, rather than for this. So a screen test builds a page with fakes in the two fields
 *       it cares about, and this class is not in the way of that.</li>
 * </ul>
 *
 * <p>What this actually is, is the wiring diagram written down in one place instead of smeared across five
 * command handlers. The alternative — each command constructing its own page — means every command holding
 * every service the page needs, which is the same list five times over and five places to forget one.
 *
 * <h2>The pages that are not here</h2>
 * There are twenty-four screens and five entry points. Everything else is reached by clicking, from a page
 * that already holds what its children need and constructs them itself. A method here per screen would be
 * this module's menu tree written out a second time, and the copy nothing enforces is the one that goes
 * stale — see {@link IHungerGamesScreensOpener}.
 */
public final class HungerGamesScreens implements IHungerGamesScreensOpener {

    /**
     * Everything the five entry pages need, handed over once.
     *
     * <p>A record rather than seventeen constructor parameters, because a positional constructor with
     * seventeen arguments — several of them the same type — is a mis-ordering that compiles. Named
     * components are checked by the compiler at the one call site that builds it.
     */
    public record Wiring(
            Brand brand,
            GameSession session,
            Supplier<HungerGamesSettings> settings,
            Supplier<TeamRules> teamRules,
            Supplier<List<BorderPhaseConfig>> borderPhases,

            GameControlService control,
            PreflightCheckService preflight,
            DeathmatchService deathmatch,
            SupplyDropService supplyDrops,
            MonsterWaveService monsterWaves,
            MannequinSimService simulation,
            SpectatorService spectator,
            SponsorTokenService sponsorTokens,
            RoundLogService roundLog,
            VirtualTime virtualTime,

            SponsorShopStore shop,
            AnnouncementService announcements,
            GamemasterMenu.Gamemasters gamemasters,
            ChatPrompts prompts,
            CustomItems customItems,
            ItemFactory itemFactory,
            ShopMenu.PlainStack plainStack,
            de.raindancer.modules.hungergames.store.TributeRoster roster,
            de.raindancer.core.ui.effect.Effects effects,
            Runnable saveCues,

            /** Applying a resolved border conflict — owned by whatever holds the phase file, not by a page. */
            Consumer<BorderMath.ApplyResult> applyBorderResolution,
            LogChannel log) {
    }

    private final Wiring wiring;

    public HungerGamesScreens(Wiring wiring) {
        this.wiring = wiring;
    }

    /**
     * {@code /hg admin} — the page a whole tournament is run from.
     *
     * <p>The permission is checked by the command before it gets here <em>and</em> again by the page's own
     * buttons. Not belt and braces for its own sake: this method is also reachable from a host plugin's hub,
     * which has no reason to know this module's permission nodes.
     */
    @Override
    public void admin(Player viewer) {
        if (!PermissionNodes.mayOpenTheAdminSuite(viewer)) {
            wiring.log().warn("{} asked for the admin suite without the permission for it.",
                    viewer.getName());
            return;
        }
        new AdminMenu(viewer, wiring.brand(), wiring.session(), wiring.control(), wiring.preflight(),
                wiring.borderPhases(), wiring.deathmatch(), wiring.settings(), wiring.supplyDrops(),
                wiring.monsterWaves(), wiring.simulation(), wiring.spectator(), wiring.gamemasters(),
                wiring.prompts(), wiring.roundLog(), wiring.virtualTime(), wiring.sponsorTokens(),
                wiring.roster(), wiring.effects(), wiring.saveCues(), wiring.customItems())
                .open();
    }

    /** {@code /hg teams} — picking a team, for a tribute in the lobby. */
    @Override
    public void teams(Player viewer) {
        new TeamsMenu(viewer, wiring.brand(), wiring.session(), wiring.teamRules(), wiring.prompts())
                .open();
    }

    /**
     * {@code /hg shop} — the sponsor shop.
     *
     * <p>Opened in preview mode for anybody who is not a living tribute. A spectator browsing what could
     * have been is harmless; a spectator who can still spend tokens on somebody is not, and the shop cannot
     * work out on its own which of the two is looking at it.
     */
    @Override
    public void shop(Player viewer) {
        boolean canBuy = wiring.session().participants().isAlive(viewer.getUniqueId());
        new ShopMenu(viewer, wiring.brand(), wiring.shop(), wiring.sponsorTokens(),
                wiring.announcements(), wiring.customItems(), wiring.itemFactory(), wiring.plainStack(),
                !canBuy)
                .open();
    }

    /** {@code /hg spectate} — where somebody who is out can go. */
    @Override
    public void spectate(Player viewer) {
        new SpectateMenu(viewer, wiring.brand(), wiring.session(), wiring.spectator()).open();
    }

    /**
     * The border conflict page, for a configuration that cannot do what it says.
     *
     * <p>Opens on the <em>first</em> conflict rather than on a list of all of them, and that is deliberate:
     * resolving one recomputes the rest, so a page showing four conflicts is showing three that may no
     * longer exist by the time somebody reaches them.
     */
    @Override
    public void borderConflict(Player viewer) {
        HungerGamesSettings now = wiring.settings().get();
        BorderSettings draft = new BorderSettings(now.borderInitialSize(), now.borderFloor(),
                now.borderEdgeSpeed(), wiring.borderPhases().get());
        Optional<Duration> round = Optional.of(now.roundDuration());

        List<BorderConflict> conflicts = BorderMath.validate(draft, round);
        if (conflicts.isEmpty()) {
            wiring.log().info("{} asked for the border conflict page, and there are no conflicts.",
                    viewer.getName());
            return;
        }
        new BorderConflictMenu(viewer, wiring.brand(), draft, round, conflicts.get(0),
                wiring.applyBorderResolution(),
                () -> wiring.log().info("{} discarded a border conflict resolution.", viewer.getName()))
                .open();
    }
}
