package de.raindancer.modules.twitch;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.twitch.service.Announcer;
import de.raindancer.modules.twitch.service.TwitchLoginStore;
import de.raindancer.modules.twitch.service.TwitchPoller;
import de.raindancer.modules.twitch.service.WatchRole;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** Everything this module has built, carried to its command. The poller is null on a server that is not the one asking Twitch. */
public record TwitchServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        RainsCore core,
        Supplier<TwitchSettings> settings,
        Announcer announcer,
        java.util.function.Supplier<TwitchPoller> poller,
        WatchRole role,
        Runnable reloadLogin,
        TwitchLoginStore logins) {
}
