package de.raindancer.modules.farmworld;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.farmworld.rules.FarmAccessRule;
import de.raindancer.modules.farmworld.service.FarmAdminService;
import de.raindancer.modules.farmworld.service.FarmTravelService;
import de.raindancer.modules.farmworld.service.NoticeService;
import de.raindancer.modules.farmworld.store.FarmWorldCatalogue;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener, a screen or a command can be handed what
 * it needs.
 *
 * <p>Data, not a god object: a record of collaborators built once by the module and passed down, and a test
 * builds one with fakes in the fields it cares about. Nothing here is static and nothing here reaches back
 * into Bukkit.
 *
 * @param settings behind a supplier rather than captured — a reload replaces the snapshot wholesale, and a
 *                 screen holding the old one would draw yesterday's warm-up on its buttons
 * @param store    the same settings, writeable, for the admin config page. Kept apart from the supplier
 *                 above on purpose: everything else on this record only ever reads
 * @param screens  opening a screen, as an interface, so nothing here depends on the menus
 */
public record FarmWorldServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Chat chat,
        Brand brand,

        Supplier<FarmWorldSettings> settings,
        SettingsStore<FarmWorldSettings> store,

        FarmWorldCatalogue catalogue,
        FarmAccessRule access,
        Travel travel,
        FarmTravelService travelling,
        FarmAdminService admin,
        NoticeService notices,

        IFarmWorldScreensOpener screens) {

    /** The settings as they are right now. */
    public FarmWorldSettings config() {
        return settings.get();
    }
}
