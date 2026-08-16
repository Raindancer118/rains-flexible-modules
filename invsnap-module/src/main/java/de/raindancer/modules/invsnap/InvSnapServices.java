package de.raindancer.modules.invsnap;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.invsnap.service.SnapshotService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener, a screen or a command can be
 * handed what it needs. See {@code MannequinServices} for why this is a record rather than a god
 * object.
 */
public record InvSnapServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,
        RainsCore core,

        Supplier<InvSnapSettings> settings,
        SettingsStore<InvSnapSettings> store,

        SnapshotService snapshots,
        IInvSnapScreensOpener screens) {

    public InvSnapSettings config() {
        return settings.get();
    }
}
