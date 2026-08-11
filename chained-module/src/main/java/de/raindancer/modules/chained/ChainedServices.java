package de.raindancer.modules.chained;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chained.rules.ChainDistanceRule;
import de.raindancer.modules.chained.service.ChainService;
import de.raindancer.modules.chained.store.ChainPairStore;
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
 *                 and a screen holding the old one would draw yesterday's max distance on its buttons
 * @param store    the same settings, writeable, for the admin config page
 * @param screens  opening a screen, as an interface, so nothing here depends on the menus
 */
public record ChainedServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Chat chat,
        Brand brand,

        Supplier<ChainedSettings> settings,
        SettingsStore<ChainedSettings> store,

        ChainPairStore pairs,
        ChainDistanceRule distance,
        ChainService chain,

        IChainedScreensOpener screens) {

    /** The settings as they are right now. */
    public ChainedSettings config() {
        return settings.get();
    }
}
