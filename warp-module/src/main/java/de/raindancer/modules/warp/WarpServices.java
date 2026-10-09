package de.raindancer.modules.warp;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import de.raindancer.modules.warp.service.TravelService;
import de.raindancer.modules.warp.service.WarpAdminService;
import de.raindancer.modules.warp.store.WarpCatalogue;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener, a screen or a command can be handed
 * what it needs.
 *
 * <p>Data, not a god object: a record of collaborators built once by the module and passed down, and
 * a test builds one with fakes in the fields it cares about. Nothing here is static and nothing here
 * reaches back into Bukkit.
 *
 * @param settings behind a supplier rather than captured — a reload replaces the snapshot wholesale,
 *                 and a screen holding the old one would draw yesterday's warm-up on its buttons
 * @param store    the same settings, writeable, for the admin config page. Kept apart from the
 *                 supplier above on purpose: everything else on this record only ever reads
 * @param screens  opening a screen, as an interface, so nothing here depends on the menus
 */
public record WarpServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Chat chat,
        Brand brand,

        Supplier<WarpSettings> settings,
        SettingsStore<WarpSettings> store,

        WarpCatalogue catalogue,
        WarpAccessRule access,
        Travel travel,
        TravelService travelling,
        WarpAdminService admin,
        de.raindancer.modules.warp.service.WarpTokens tokens,
        de.raindancer.modules.warp.service.ClaimWarpDirectory claimWarps,
        de.raindancer.modules.warp.service.WarpVisitFees visits,
        de.raindancer.modules.warp.service.WarpRentService rent,

        IWarpScreensOpener screens) {

    /**
     * Whether this player would be let in at a claim's warp: its world is loaded and the claim's own
     * teleport-in rules admit them. The arrival is checked again by the claim itself.
     */
    public java.util.function.Predicate<de.raindancer.core.world.poi.Poi> arriving(org.bukkit.entity.Player who) {
        // The claim standing there has to be the claim the warp is for: a point outside its claim is
        // covered by none of that claim's rules, and must not be offered under its name.
        return point -> point.location()
                .filter(at -> core.land().areaAt(at)
                        .map(area -> area.id().equals(de.raindancer.core.world.poi.ClaimWarps.claimOf(point)))
                        .orElse(false))
                .map(at -> core.land().mayTeleportInto(who, at))
                .orElse(false);
    }

    /** The settings as they are right now. */
    public WarpSettings config() {
        return settings.get();
    }
}
