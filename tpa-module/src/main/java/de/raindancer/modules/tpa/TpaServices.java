package de.raindancer.modules.tpa;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.tpa.rules.TpaAskingRule;
import de.raindancer.modules.tpa.service.BackService;
import de.raindancer.modules.tpa.service.TpaPrefsService;
import de.raindancer.modules.tpa.service.TpaRequestService;
import de.raindancer.modules.tpa.store.TpaRequests;
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
 * @param settings behind a supplier rather than captured: a reload replaces the snapshot wholesale, and
 *                 a screen holding the old one would draw yesterday's expiry on its buttons
 * @param screens  opening a screen, as an interface, so nothing here depends on the menus
 */
public record TpaServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Chat chat,
        Brand brand,

        Supplier<TpaSettings> settings,

        TpaRequests requests,
        TpaAskingRule rule,
        Travel travel,
        TpaRequestService asking,
        TpaPrefsService prefs,
        BackService back,

        ITpaScreensOpener screens) {

    /** The settings as they are right now. */
    public TpaSettings config() {
        return settings.get();
    }
}
