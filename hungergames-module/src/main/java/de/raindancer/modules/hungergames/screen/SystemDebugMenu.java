package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.hungergames.service.RoundLogService;
import de.raindancer.modules.hungergames.service.SponsorTokenService;
import de.raindancer.modules.hungergames.service.VirtualTime;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * System status and the two debug tools an admin actually reaches for mid-test: the round's own log file,
 * and speeding the clock up.
 *
 * <h2>What the source's System &amp; Debug page had that this one deliberately does not</h2>
 * The plugin this replaces also put "reload loot", "apply the server whitelist", four sound/loot/drop test
 * buttons and the double-confirmed FLIPTABLE server reset here. None of those has a home in this wave of
 * the port: the loot catalogue, sponsor beacons and the arena builder that FLIPTABLE would have to rebuild
 * afterwards are other agents' services, some not landed yet. Wiring buttons to services that do not exist
 * would be exactly the "looks finished, does nothing" failure this port exists to avoid — so this page
 * offers only what {@link RoundLogService}, {@link VirtualTime} and {@link SponsorTokenService} can
 * actually do today, and says so rather than pretending otherwise.
 *
 * <h2>Why the speed change is Core's {@link AmountChooser} rather than four fixed buttons</h2>
 * ×1/×2/×4/×8 was the source's whole range; {@link AmountChooser} offers the same kind of stepped choice
 * without this page maintaining a second, smaller idea of what a sensible multiplier is.
 */
public final class SystemDebugMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameSession session;
    private final RoundLogService roundLog;
    private final VirtualTime virtualTime;
    private final SponsorTokenService sponsorTokens;

    public SystemDebugMenu(Player viewer, Brand brand, Menu parent, GameSession session,
                           RoundLogService roundLog, VirtualTime virtualTime,
                           SponsorTokenService sponsorTokens) {
        super(viewer, brand, parent);
        this.session = session;
        this.roundLog = roundLog;
        this.virtualTime = virtualTime;
        this.sponsorTokens = sponsorTokens;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<red>System & Debug");
    }

    @Override
    public String breadcrumb() {
        return "System & Debug";
    }

    @Override
    protected void render() {
        if (!PermissionNodes.isAdmin(viewer)) {
            set(22, Icons.locked(Icons.of(Material.BARRIER, "<red>Admins only",
                    "<gray>This page is not yours to open."), "Requires " + PermissionNodes.ADMIN));
            return;
        }

        set(4, Icons.of(Material.REDSTONE, "<gold>System",
                "<gray>Phase: " + session.phase(),
                "<gray>Round log: " + roundLog.currentFile().getFileName(),
                "<gray>Round speed: ×" + virtualTime.multiplier()
                        + (virtualTime.isRunning() ? " (running)" : " (stopped)")));

        set(10, Icons.of(Material.CLOCK, "<yellow>Change the round speed",
                        "<gray>Current: ×" + virtualTime.multiplier(),
                        "<dark_gray>Speeds up the clock, the border and every timer that reads it."),
                click -> new AmountChooser(viewer, brand(), this, "Round speed multiplier",
                        1, 8, (int) Math.round(virtualTime.multiplier()),
                        chosen -> {
                            virtualTime.setMultiplier(chosen);
                            refresh();
                        }).open());

        // A label, not a button — so it is drawn as one. It had a name and lore and no click handler, which
        // is a button that answers a click with nothing: pressed, then pressed again, then reported as broken.
        // Icons.locked is how this server says "this is information".
        set(12, Icons.locked(Icons.of(Material.WRITABLE_BOOK, "<yellow>Round log",
                        "<gray>" + roundLog.currentFile(),
                        "<dark_gray>Read it on the server; it is not openable from here."),
                "Nothing to click"));

        set(14, Icons.of(Material.NETHER_STAR, "<yellow>Give yourself a sponsor token",
                        "<gray>For testing the shop without waiting for a wave."),
                click -> {
                    sponsorTokens.giveManually(viewer.getName(), viewer, 1);
                    refresh();
                });
    }

    @Override
    public String describe() {
        return "round-speed and the round log — the debug tools this wave of the port actually has";
    }
}
