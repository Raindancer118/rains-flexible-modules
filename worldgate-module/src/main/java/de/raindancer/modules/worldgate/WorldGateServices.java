package de.raindancer.modules.worldgate;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.worldgate.service.WorldGateService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener or a command can be handed what it
 * needs.
 *
 * <p>Data, not a god object: a record of collaborators built once by the module and passed down. A
 * test builds one with fakes in the fields it cares about. Nothing here is static and nothing here
 * reaches back into Bukkit.
 *
 * @param settings behind a supplier rather than captured — a reload replaces the snapshot wholesale,
 *                 and a command holding the old one would enforce yesterday's world names
 * @param store    the same settings, writeable, for {@code /settings}
 */
public record WorldGateServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,

        Supplier<WorldGateSettings> settings,
        SettingsStore<WorldGateSettings> store,

        WorldGateService gate) {

    /** The settings as they are right now. */
    public WorldGateSettings config() {
        return settings.get();
    }
}
