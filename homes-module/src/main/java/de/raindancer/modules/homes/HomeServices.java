package de.raindancer.modules.homes;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.homes.rules.HomeLimitRule;
import de.raindancer.modules.homes.rules.HomeNameRule;
import de.raindancer.modules.homes.service.HomeKeepingService;
import de.raindancer.modules.homes.service.HomeTravelService;
import de.raindancer.modules.homes.store.HomeCatalogue;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener, a screen or a command can be handed
 * what it needs.
 *
 * <p>Data, not a god object: a record of collaborators built once by the module and passed down, and a
 * test builds one with fakes in the fields it cares about. Nothing here is static and nothing here
 * reaches back into Bukkit.
 *
 * <p>The thing this replaces was the {@code JavaPlugin} subclass itself, handed to every listener as a
 * {@code Homes} interface — which worked, and grew a method every time anything needed anything.
 *
 * @param settings behind a supplier rather than captured: a reload replaces the snapshot wholesale,
 *                 and a screen holding the old one would draw yesterday's limit on its counter
 * @param screens  opening a screen, as an interface, so nothing here depends on the menus
 */
public record HomeServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Chat chat,
        Brand brand,

        Supplier<HomeSettings> settings,

        HomeCatalogue homes,
        HomeNameRule names,
        HomeLimitRule limits,
        Travel travel,
        HomeTravelService travelling,
        HomeKeepingService keeping,

        IHomeScreensOpener screens) {

    /** The settings as they are right now. */
    public HomeSettings config() {
        return settings.get();
    }
}
