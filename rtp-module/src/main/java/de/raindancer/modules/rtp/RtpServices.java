package de.raindancer.modules.rtp;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.rtp.service.RtpLocationPoolService;
import de.raindancer.modules.rtp.service.RtpService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener, a screen or a command can be handed
 * what it needs.
 *
 * <p>Data, not a god object: a record of collaborators built once by the module and passed down. A
 * test builds one with fakes in the fields it cares about. Nothing here is static and nothing here
 * reaches back into Bukkit.
 *
 * @param settings behind a supplier rather than captured — a reload replaces the snapshot wholesale,
 *                 and a command holding the old one would enforce yesterday's radius
 * @param store    the same settings, writeable, for {@code /settings}
 * @param screens  opening a screen, as an interface, so nothing here depends on the menus
 */
public record RtpServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,

        Supplier<RtpSettings> settings,
        SettingsStore<RtpSettings> store,

        RtpService rtp,
        RtpLocationPoolService locations,
        IRtpScreensOpener screens) {

    /** The settings as they are right now. */
    public RtpSettings config() {
        return settings.get();
    }
}
